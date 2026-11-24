package checker;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import javax.swing.SwingUtilities;

//The Windows file dialog (the Explorer one, with the navigation pane, address bar and search), called through Java's
//foreign function API: on Windows, AWT's FileDialog shows the old Windows XP-style dialog. Nothing extra to install.
//Falls back to AWT's FileDialog when the Windows one cannot be shown (another OS, or the call fails).
public final class WindowsFileDialog {

    private WindowsFileDialog() {
    }

    //null = cancelled. ext without the dot, e.g. "mdb".
    public static File open(Component parent, String title, File dir, String filter_name, String ext) {
        return pick(parent, false, title, dir, null, filter_name, ext);
    }

    //null = cancelled. The dialog asks before overwriting an existing file.
    public static File save(Component parent, String title, File dir, String name, String filter_name, String ext) {
        return pick(parent, true, title, dir, name, filter_name, ext);
    }

    private static File pick(Component parent, boolean save, String title, File dir, String name, String filter_name, String ext) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            try {
                long owner = ownerWindow();
                return onComThread(() -> show(save, owner, title, dir, name, filter_name, ext));
            } catch (Throwable e) {
                System.err.println("Windows file dialog unavailable, using the Java one: " + e);
            }
        }
        return awt(parent, save, title, dir, name, ext);
    }

    private static File awt(Component parent, boolean save, String title, File dir, String name, String ext) {
        Window owner = parent == null ? null : parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent);
        FileDialog dialog = new FileDialog(owner instanceof Frame ? (Frame) owner : null, title, save ? FileDialog.SAVE : FileDialog.LOAD);
        if (dir != null) dialog.setDirectory(dir.getPath());
        dialog.setFile(name != null ? name : "*." + ext);
        dialog.setVisible(true);
        return dialog.getFile() == null ? null : new File(dialog.getDirectory(), dialog.getFile());
    }

    //-------------------------------------------------------------------------------------------------
    //COM: IFileOpenDialog / IFileSaveDialog

    private static final String CLSID_FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7";
    private static final String CLSID_FILE_SAVE_DIALOG = "C0B4E2F3-BA21-4773-8DBA-335EC946EB8B";
    private static final String IID_IFILE_DIALOG = "42F85136-DB7E-439C-85F1-E4075D135FC8";
    private static final String IID_ISHELL_ITEM = "43826D1E-E718-42EE-BC55-A1E261C37BFE";

    //IFileDialog vtable slots (IUnknown 0-2, IModalWindow 3)
    private static final int RELEASE = 2, SHOW = 3, SET_FILE_TYPES = 4, SET_OPTIONS = 9, GET_OPTIONS = 10, SET_FOLDER = 12,
        SET_FILE_NAME = 15, SET_TITLE = 17, GET_RESULT = 20, SET_DEFAULT_EXTENSION = 22;
    private static final int SHELL_ITEM_GET_DISPLAY_NAME = 5;

    private static final int FOS_OVERWRITEPROMPT = 0x2, FOS_NOCHANGEDIR = 0x8, FOS_FORCEFILESYSTEM = 0x40,
        FOS_PATHMUSTEXIST = 0x800, FOS_FILEMUSTEXIST = 0x1000;
    private static final int SIGDN_FILESYSPATH = 0x80058000;
    private static final int ERROR_CANCELLED = 0x800704C7; //HRESULT_FROM_WIN32(ERROR_CANCELLED)
    private static final int COINIT_APARTMENTTHREADED = 0x2, COINIT_DISABLE_OLE1DDE = 0x4, CLSCTX_INPROC_SERVER = 0x1;

    private static final Linker LINKER = Linker.nativeLinker();

    private static class Native { //loaded on first use, so other systems never touch the Windows libraries
        static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", Arena.global());
        static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32", Arena.global());
        static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
        static final MethodHandle CO_INITIALIZE_EX = handle(OLE32, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle CO_UNINITIALIZE = handle(OLE32, "CoUninitialize", FunctionDescriptor.ofVoid());
        static final MethodHandle CO_CREATE_INSTANCE = handle(OLE32, "CoCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle CO_TASK_MEM_FREE = handle(OLE32, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle SH_CREATE_ITEM = handle(SHELL32, "SHCreateItemFromParsingName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle GET_FOREGROUND_WINDOW = handle(USER32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));
        static final MethodHandle GET_WINDOW_PROCESS = handle(USER32, "GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

        private static MethodHandle handle(SymbolLookup lib, String name, FunctionDescriptor fd) {
            return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
        }
    }

    //The checker's window, so the dialog is modal to it: the window the user just clicked in, when it is ours
    private static long ownerWindow() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hwnd = (MemorySegment) Native.GET_FOREGROUND_WINDOW.invokeExact();
            if (hwnd.address() == 0) return 0;
            MemorySegment pid = arena.allocate(JAVA_INT);
            int thread = (int) Native.GET_WINDOW_PROCESS.invokeExact(hwnd, pid);
            return thread != 0 && pid.get(JAVA_INT, 0) == (int) ProcessHandle.current().pid() ? hwnd.address() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    private interface Work {
        File run() throws Throwable;
    }

    //COM dialogs need a single-threaded apartment, so the dialog runs on its own thread. On the Swing thread a
    //secondary loop keeps the window painting while the dialog is open.
    private static File onComThread(Work work) throws Throwable {
        Object[] result = new Object[2];
        SecondaryLoop loop = EventQueue.isDispatchThread() ? Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop() : null;
        Thread thread = new Thread(() -> {
            try {
                result[0] = work.run();
            } catch (Throwable e) {
                result[1] = e;
            } finally {
                if (loop != null) loop.exit();
            }
        }, "Windows file dialog");
        thread.setDaemon(true);
        thread.start();
        if (loop != null) loop.enter();
        thread.join();
        if (result[1] != null) throw (Throwable) result[1];
        return (File) result[0];
    }

    private static File show(boolean save, long owner, String title, File dir, String name, String filter_name, String ext) throws Throwable {
        int init = (int) Native.CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            check((int) Native.CO_CREATE_INSTANCE.invokeExact(guid(arena, save ? CLSID_FILE_SAVE_DIALOG : CLSID_FILE_OPEN_DIALOG),
                MemorySegment.NULL, CLSCTX_INPROC_SERVER, guid(arena, IID_IFILE_DIALOG), out), "CoCreateInstance");
            MemorySegment dialog = out.get(ADDRESS, 0);
            try {
                check(com(dialog, GET_OPTIONS, out), "GetOptions");
                int options = out.get(JAVA_INT, 0) | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST | FOS_NOCHANGEDIR
                    | (save ? FOS_OVERWRITEPROMPT : FOS_FILEMUSTEXIST);
                check(com(dialog, SET_OPTIONS, options), "SetOptions");
                check(com(dialog, SET_TITLE, text(arena, title)), "SetTitle");

                MemorySegment filters = arena.allocate(ADDRESS.byteSize() * 4);
                filters.setAtIndex(ADDRESS, 0, text(arena, filter_name + " (*." + ext + ")"));
                filters.setAtIndex(ADDRESS, 1, text(arena, "*." + ext));
                filters.setAtIndex(ADDRESS, 2, text(arena, "All files (*.*)"));
                filters.setAtIndex(ADDRESS, 3, text(arena, "*.*"));
                check(com(dialog, SET_FILE_TYPES, 2, filters), "SetFileTypes");
                check(com(dialog, SET_DEFAULT_EXTENSION, text(arena, ext)), "SetDefaultExtension");
                if (name != null) check(com(dialog, SET_FILE_NAME, text(arena, name)), "SetFileName");

                if (dir != null && dir.isDirectory()) { //a missing folder just leaves the dialog where Windows last was
                    int hr = (int) Native.SH_CREATE_ITEM.invokeExact(text(arena, dir.getAbsolutePath()), MemorySegment.NULL,
                        guid(arena, IID_ISHELL_ITEM), out);
                    if (hr >= 0) {
                        MemorySegment folder = out.get(ADDRESS, 0);
                        com(dialog, SET_FOLDER, folder);
                        com(folder, RELEASE);
                    }
                }

                int hr = com(dialog, SHOW, MemorySegment.ofAddress(owner));
                if (hr == ERROR_CANCELLED) return null;
                check(hr, "Show");

                check(com(dialog, GET_RESULT, out), "GetResult");
                MemorySegment item = out.get(ADDRESS, 0);
                try {
                    check(com(item, SHELL_ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, out), "GetDisplayName");
                    MemorySegment path = out.get(ADDRESS, 0);
                    try {
                        return new File(path.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE));
                    } finally {
                        Native.CO_TASK_MEM_FREE.invokeExact(path);
                    }
                } finally {
                    com(item, RELEASE);
                }
            } finally {
                com(dialog, RELEASE);
            }
        } finally {
            if (init >= 0) Native.CO_UNINITIALIZE.invokeExact();
        }
    }

    //Calls a COM method by vtable slot; arguments are MemorySegment (pointers) or Integer. Returns the HRESULT.
    private static int com(MemorySegment object, int slot, Object... args) throws Throwable {
        MemorySegment vtable = object.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * (slot + 1));
        MemoryLayout[] layouts = new MemoryLayout[args.length + 1];
        Object[] values = new Object[args.length + 1];
        layouts[0] = ADDRESS;
        values[0] = object;
        for (int i = 0; i < args.length; i++) {
            layouts[i + 1] = args[i] instanceof Integer ? JAVA_INT : ADDRESS;
            values[i + 1] = args[i];
        }
        MethodHandle method = LINKER.downcallHandle(vtable.getAtIndex(ADDRESS, slot), FunctionDescriptor.of(JAVA_INT, layouts));
        return (int) method.invokeWithArguments(values);
    }

    private static void check(int hr, String what) throws IOException {
        if (hr < 0) throw new IOException(what + " failed: 0x" + Integer.toHexString(hr));
    }

    private static MemorySegment text(Arena arena, String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    //GUID layout: Data1 (4 bytes), Data2, Data3 (2 each) little-endian, then Data4 (8 bytes) as written
    private static MemorySegment guid(Arena arena, String text) {
        UUID u = UUID.fromString(text);
        ByteBuffer b = ByteBuffer.allocate(16);
        long hi = u.getMostSignificantBits();
        b.order(ByteOrder.LITTLE_ENDIAN).putInt((int) (hi >>> 32)).putShort((short) (hi >>> 16)).putShort((short) hi);
        b.order(ByteOrder.BIG_ENDIAN).putLong(u.getLeastSignificantBits());
        MemorySegment g = arena.allocate(16);
        g.copyFrom(MemorySegment.ofArray(b.array()));
        return g;
    }
}
