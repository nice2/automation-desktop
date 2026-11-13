import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public class DataEntryBot extends Robot {

    //Lets the bot pause whatever is driving it (ConfigBot) and wait for the user to resume
    public interface PauseHandler {
        void setIsPaused(boolean status);
        boolean getIsPaused();
    }

    private int delay = 200; //default 200. Delay time between key presses
    private double DELAY_STRENGTH = 0; //Default 0. Multiply delay by this to determine final delay time

    //Ready check - instead of only sleeping a fixed time, wait until the configurator is done processing.
    //Off unless enableReadyCheck() is called, so bots that don't opt in behave exactly as before.
    private boolean ready_check = false;
    private int ready_timeout = 30000; //ms the configurator may stay busy before the bot pauses
    private PauseHandler pause_handler;
    private volatile long target_pid = 0; //process the bot is typing into, locked on the first key press or set by setTargetWindow
    private volatile long main_window = 0; //window picked in the GUI
    private volatile long last_window = 0; //last configurator window (or dialog) the bot typed into
    private volatile boolean stopped = false;
    private volatile boolean dry_run = false; //work out what would be typed without pressing anything

    //Set when the configurator lost focus partway through a step. Focus loss can close menus/editors the
    //step had opened, so the rest of the step is skipped and ConfigBot redoes it from a clean state.
    private volatile boolean aborted = false;
    private int keys_in_step = 0;

    //A ping slower than this means the configurator was busy, so the quiet window starts over
    private static final int BUSY_THRESHOLD_MS = 15;

    //Windows that should never be typed into (the console running this bot, the desktop/taskbar)
    private static final Set<String> NON_TARGETS = Set.of(
        "cmd.exe", "conhost.exe", "openconsole.exe", "windowsterminal.exe", "powershell.exe", "pwsh.exe",
        "java.exe", "javaw.exe", "explorer.exe"
    );

    public DataEntryBot(int delay) throws Exception {
        this.delay = delay;
    }

    //Turn on the ready check. The pause handler is paused when the configurator stays busy or loses focus.
    public void enableReadyCheck(int ready_timeout) {
        this.ready_check = true;
        this.ready_timeout = ready_timeout;
    }

    //Kept for older callers
    public void enableReadyCheck(int ready_timeout, PauseHandler handler) {
        setPauseHandler(handler);
        enableReadyCheck(ready_timeout);
    }

    //Pausing the handler (e.g. a Pause button) stops the bot before its next key press
    public void setPauseHandler(PauseHandler handler) {
        this.pause_handler = handler;
    }

    //Stop sending input for good. Any further key presses are ignored.
    public void stop() {
        stopped = true;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void setDryRun(boolean dry_run) {
        this.dry_run = dry_run;
    }

    //Marks the start of a step (one device or one row) that can be redone if interrupted
    public void beginStep() {
        aborted = false;
        keys_in_step = 0;
    }

    //Skip the rest of the current step so ConfigBot can redo it
    protected void abortStep() {
        aborted = true;
    }

    //True if the current step was interrupted and should be redone. Clears the flag.
    public boolean consumeAborted() {
        boolean was = aborted;
        aborted = false;
        return was;
    }

    //Wait until the configurator has been idle for ms (or just sleep when the ready check is off)
    public void settle(int ms) {
        if(stopped || dry_run || aborted) {
            return;
        }

        if(ready_check) {
            waitForReady(ms);
        }
        else {
            this.delay(ms);
        }
    }

    //Only type into this window's program
    public void setTargetWindow(long hwnd) {
        main_window = hwnd;
        last_window = hwnd;
        target_pid = Win32.pidOf(MemorySegment.ofAddress(hwnd));
    }

    //Bring the configurator back to the front, ideally the exact window (dialog) the bot was typing into
    public void focusTarget() {
        long hwnd = Win32.isWindow(last_window) ? last_window : main_window;
        if(hwnd == 0 || !Win32.isWindow(hwnd)) {
            return;
        }

        if(target_pid != 0 && Win32.pidOf(Win32.foreground()) == target_pid) {
            return; //already in front
        }

        Win32.bringToFront(hwnd);
        this.delay(300); //let the window finish activating before typing
    }

    //Give key and press count, press and release key press count times, and multiply delay if provided.
    //Returns true if every press was sent (false if stopped or the step was interrupted first).
    public boolean pressKey(int key, int press_count, double delay_strength)
    {
        try {
            for(int i = 0; i < press_count; i++) {
                if(!beforeInput()) {
                    return false;
                }

                this.keyPress(key);
                this.keyRelease(key);
                keys_in_step++;

                afterInput(delay_strength);
            }
            return true;
        }catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean pressKey(int key, int press_count) {
        return pressKey(key, press_count, DELAY_STRENGTH);
    }

    public boolean pressKey(int key) {
        return pressKey(key, 1, DELAY_STRENGTH);
    }

    //Press a key while holding a modifier, e.g. Shift+F10
    public boolean pressCombo(int modifier, int key, double delay_strength) {
        if(!beforeInput()) {
            return false;
        }

        this.keyPress(modifier);
        this.keyPress(key);
        this.keyRelease(key);
        this.keyRelease(modifier);
        keys_in_step++;

        afterInput(delay_strength);
        return true;
    }

    //Copies text to clipboard and simulates pasting it
    public void pasteText(String text) {
        if(!beforeInput()) {
            return;
        }

        if(!setClipboard(text)) {
            System.err.println("Could not set clipboard for: " + text);
            return;
        }

        this.keyPress(KeyEvent.VK_CONTROL);
        this.keyPress(KeyEvent.VK_V);
        this.keyRelease(KeyEvent.VK_V);
        this.keyRelease(KeyEvent.VK_CONTROL);
        keys_in_step++;

        //Strength 1 = the old fixed delay. With the ready check on, this also makes sure the paste
        //is processed before the next pasteText overwrites the clipboard.
        afterInput(1);
    }

    public void clearText() {
        if(!beforeInput()) {
            return;
        }

        this.keyPress(KeyEvent.VK_CONTROL);
        this.keyPress(KeyEvent.VK_A);
        this.keyRelease(KeyEvent.VK_CONTROL);
        this.keyRelease(KeyEvent.VK_A);
        this.keyPress(KeyEvent.VK_DELETE);
        this.keyRelease(KeyEvent.VK_DELETE);
        keys_in_step++;

        afterInput(DELAY_STRENGTH);
    }

    //Another program can briefly hold the clipboard open, which makes setContents throw. Retry a few times.
    protected boolean setClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

        for(int attempt = 0; attempt < 10; attempt++) {
            try {
                clipboard.setContents(selection, selection);
                return true;
            } catch (IllegalStateException e) {
                this.delay(50);
            }
        }
        return false;
    }

    //Runs before every key press. Returns false if no input should be sent.
    private boolean beforeInput() {
        if(stopped || dry_run || aborted) {
            return false;
        }

        if(ready_check) {
            //Make sure the configurator is idle and in front before typing into it
            waitForReady(0);
        }

        return !stopped && !aborted;
    }

    //Runs after every key press
    private void afterInput(double delay_strength) {
        int wait = (int) (delay * delay_strength);

        if(ready_check) {
            //The old fixed delay becomes a quiet window: it only counts down while the
            //configurator is responsive, and starts over whenever it is busy.
            waitForReady(wait);
        }
        else {
            this.delay(wait);
        }
    }

    //Wait until the configurator has been responsive for quiet_ms in a row.
    //Pauses (and waits for the user) if it stays busy past the timeout or another window takes focus.
    public void waitForReady(int quiet_ms) {
        long start = System.currentTimeMillis();
        long deadline = start + ready_timeout;
        long quiet_since = start;

        while(!stopped) {
            MemorySegment window = Win32.foreground();

            if(!isTargetWindow(window)) {
                boolean mid_step = keys_in_step > 0;
                pause(target_pid == 0
                    ? "The configurator is not the active window, so there is nothing safe to type into."
                    : "The configurator is no longer the active window."
                        + (mid_step ? " It lost focus partway through a step, so the bot will back out and redo that step when resumed." : ""));

                if(mid_step && !stopped) {
                    //Menus/editors the step opened may have closed - skip the rest of the step so it can be redone
                    abortStep();
                    return;
                }

                start = System.currentTimeMillis();
                deadline = start + ready_timeout;
                quiet_since = start;
                continue;
            }

            last_window = window.address();

            long ping_start = System.currentTimeMillis();
            long remaining = deadline - ping_start;

            if(remaining <= 0 || !Win32.ping(window, (int) Math.min(remaining, Integer.MAX_VALUE))) {
                pause("The configurator is still busy after " + (ready_timeout / 1000) + " seconds.");
                start = System.currentTimeMillis();
                deadline = start + ready_timeout;
                quiet_since = start;
                continue;
            }

            long ping_end = System.currentTimeMillis();

            if(ping_end - ping_start > BUSY_THRESHOLD_MS) {
                quiet_since = ping_end; //it was busy, start the quiet window over
            }
            else if(ping_end - quiet_since >= quiet_ms) {
                return;
            }

            this.delay((int) Math.max(1, Math.min(10, quiet_ms - (ping_end - quiet_since))));
        }
    }

    //Locks onto the first real program the bot types into, then only accepts windows from that program
    private boolean isTargetWindow(MemorySegment window) {
        long pid = Win32.pidOf(window);

        if(target_pid == 0) {
            String name = processName(pid);
            if(pid == 0 || pid == ProcessHandle.current().pid() || NON_TARGETS.contains(name.toLowerCase())) {
                return false;
            }

            target_pid = pid;
            System.out.println("Typing into " + (name.isEmpty() ? "process" : name) + " (pid " + pid + ")");
            return true;
        }

        return pid == target_pid;
    }

    static String processName(long pid) {
        return ProcessHandle.of(pid)
            .flatMap(p -> p.info().command())
            .map(cmd -> cmd.substring(Math.max(cmd.lastIndexOf('\\'), cmd.lastIndexOf('/')) + 1))
            .orElse("");
    }

    private void pause(String reason) {
        System.out.println("----------------------------------------------------------------");
        System.out.println("PAUSED: " + reason);

        if(pause_handler == null) {
            System.out.println("Stopping - nothing to resume from.");
            stopped = true;
            return;
        }

        System.out.println("Check the configurator, then press Resume (or click back into it and press the start F-key). Press ` or Stop to quit.");
        System.out.println("----------------------------------------------------------------");

        pause_handler.setIsPaused(true);
        waitWhilePaused();
    }

    private void waitWhilePaused() {
        while(pause_handler.getIsPaused() && !stopped) {
            this.delay(100);
        }
    }

    //A top level window, for picking the configurator in the GUI
    public record WindowInfo(long hwnd, long pid, String title, String process) {
        @Override
        public String toString() {
            return title;
        }
    }

    //Visible, titled, top level windows of other programs
    public static List<WindowInfo> listWindows() {
        List<WindowInfo> windows = new ArrayList<>();
        long own_pid = ProcessHandle.current().pid();
        long hwnd = 0;

        while((hwnd = Win32.nextTopLevelWindow(hwnd)) != 0) {
            if(!Win32.isVisible(hwnd) || Win32.hasOwner(hwnd)) {
                continue;
            }

            String title = Win32.windowText(hwnd);
            long pid = Win32.pidOf(MemorySegment.ofAddress(hwnd));
            if(title.isBlank() || pid == own_pid) {
                continue;
            }

            windows.add(new WindowInfo(hwnd, pid, title, processName(pid)));
        }

        return windows;
    }

    //Minimal Windows calls through Java's built in Foreign Function API (Java 22+), no extra jars needed
    static final class Win32 {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());

        private static final MethodHandle GET_FOREGROUND_WINDOW = function("GetForegroundWindow",
            FunctionDescriptor.of(ADDRESS));
        private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = function("GetWindowThreadProcessId",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle SEND_MESSAGE_TIMEOUT = function("SendMessageTimeoutW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle FIND_WINDOW_EX = function("FindWindowExW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle GET_WINDOW_TEXT = function("GetWindowTextW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle IS_WINDOW_VISIBLE = function("IsWindowVisible",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle IS_WINDOW = function("IsWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle IS_ICONIC = function("IsIconic",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle GET_WINDOW = function("GetWindow",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle SHOW_WINDOW = function("ShowWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle SET_FOREGROUND_WINDOW = function("SetForegroundWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

        private static final int WM_NULL = 0x0000;
        private static final int SMTO_NORMAL = 0x0000; //not SMTO_ABORTIFHUNG: a long busy spell would count as hung and return early
        private static final int GW_OWNER = 4;
        private static final int SW_RESTORE = 9;

        private static MethodHandle function(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(USER32.find(name).orElseThrow(), descriptor);
        }

        private static MemorySegment hwnd(long address) {
            return MemorySegment.ofAddress(address);
        }

        static MemorySegment foreground() {
            try {
                return (MemorySegment) GET_FOREGROUND_WINDOW.invokeExact();
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        static long pidOf(MemorySegment window) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_INT);
                int thread_id = (int) GET_WINDOW_THREAD_PROCESS_ID.invokeExact(window, out);
                return thread_id == 0 ? 0 : Integer.toUnsignedLong(out.get(JAVA_INT, 0));
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        //Sends a no-op message and returns once the window's UI thread handles it.
        //Returns false if it is still busy after timeout_ms.
        static boolean ping(MemorySegment window, int timeout_ms) {
            try {
                long result = (long) SEND_MESSAGE_TIMEOUT.invokeExact(window, WM_NULL, 0L, 0L, SMTO_NORMAL, timeout_ms, MemorySegment.NULL);
                return result != 0;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        //Walks the top level windows: pass 0 to start, then the previous result. Returns 0 at the end.
        static long nextTopLevelWindow(long after) {
            try {
                MemorySegment next = (MemorySegment) FIND_WINDOW_EX.invokeExact(MemorySegment.NULL, hwnd(after), MemorySegment.NULL, MemorySegment.NULL);
                return next.address();
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        static String windowText(long window) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(JAVA_CHAR, 512);
                int length = (int) GET_WINDOW_TEXT.invokeExact(hwnd(window), buffer, 512);
                char[] chars = new char[Math.max(0, length)];
                for(int i = 0; i < chars.length; i++) {
                    chars[i] = buffer.getAtIndex(JAVA_CHAR, i);
                }
                return new String(chars);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        static boolean isVisible(long window) {
            try {
                return (int) IS_WINDOW_VISIBLE.invokeExact(hwnd(window)) != 0;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        static boolean isWindow(long window) {
            if(window == 0) {
                return false;
            }
            try {
                return (int) IS_WINDOW.invokeExact(hwnd(window)) != 0;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        static boolean hasOwner(long window) {
            try {
                MemorySegment owner = (MemorySegment) GET_WINDOW.invokeExact(hwnd(window), GW_OWNER);
                return owner.address() != 0;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        //Restores the window if minimized and brings it to the front.
        //Windows only allows this while our own window is in front (e.g. right after a button click).
        static boolean bringToFront(long window) {
            try {
                if((int) IS_ICONIC.invokeExact(hwnd(window)) != 0) {
                    int ignored = (int) SHOW_WINDOW.invokeExact(hwnd(window), SW_RESTORE);
                }
                return (int) SET_FOREGROUND_WINDOW.invokeExact(hwnd(window)) != 0;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }
}
