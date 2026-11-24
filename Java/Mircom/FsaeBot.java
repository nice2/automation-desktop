import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;

import org.ini4j.Ini;

//Types one FSAE step into the configurator: input zone tags/types, the Normal zones' logic, or the graphic adder.
//Works through the same lists the GUI shows (FsaePlan), starting at any row.
public class FsaeBot extends Thread implements DataEntryBot.PauseHandler {

    public enum Step {
        INPUT_ZONES("Input zones"),
        LOGIC("Logic"),
        GRAPHIC_ADDER("Graphic adder");

        final String label;

        Step(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    //Lets the GUI follow along. Called from the bot thread.
    public interface Listener {
        void onProgress(Step step, int index, int total);
        //reason: what the user has to do before pressing Resume, or null if the ready check paused (reason in the log)
        void onPausedChanged(boolean paused, String reason);
        void onFinished(boolean completed, String message);
    }

    //Shared with the zone list bot (settings.ini); FSAE-only multipliers stay here
    private int DELAY = 200;
    private double DEVICE_UPDATE_DELAY_STRENGTH = 1.5;
    private boolean WAIT_FOR_READY = true;
    private int READY_TIMEOUT = 30000;
    private static final double TAB_DELAY_STRENGTH = 1.2; //after Tab in the logic window, so the clipboard isn't overwritten too early
    private static final double SCROLL_DELAY_STRENGTH = 0.4; //moving down the graphic adder rows
    private static final int MAX_REDOS = 3;

    private final FsaePlan plan;
    private final Step step;
    private final int start_index;
    private final DataEntryBot bot;
    private Listener listener;

    private volatile boolean is_running = false;
    private volatile boolean is_paused = false;
    private volatile boolean pause_requested = false;
    private String finish_message = null;

    public FsaeBot(FsaePlan plan, Step step, int start_index) throws Exception {
        super("fsae-" + step.name().toLowerCase());
        this.plan = plan.copy();
        this.step = step;
        this.start_index = Math.max(0, start_index);
        readSettings();
        bot = new DataEntryBot(DELAY);
        bot.setPauseHandler(this);
        if(WAIT_FOR_READY) {
            bot.enableReadyCheck(READY_TIMEOUT);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTargetWindow(long hwnd) {
        bot.setTargetWindow(hwnd);
    }

    public void focusTarget() {
        bot.focusTarget();
    }

    @Override
    public void run() {
        boolean completed = false;
        is_running = true;
        System.out.println("Starting FSAE " + step.label.toLowerCase() + " (" + plan.panel.name() + ")");

        try {
            switch(step) {
                case INPUT_ZONES:
                    runInputZones();
                    break;
                case LOGIC:
                    runLogic();
                    break;
                case GRAPHIC_ADDER:
                    runAdder();
                    break;
            }
            completed = is_running && !bot.isStopped();
            System.out.println("FSAE " + step.label.toLowerCase() + (completed ? " complete" : " stopped"));
        } catch (Exception e) {
            e.printStackTrace();
            finish_message = "Stopped by an error - see the log.";
        } finally {
            is_running = false;
            is_paused = false;
            if(listener != null) {
                listener.onFinished(completed, finish_message);
            }
        }
    }

    // ---------------------------------------------------------------- step 1: input zones

    private void runInputZones() throws InterruptedException {
        List<FsaePlan.InputZone> zones = plan.inputZones();

        for(int i = start_index; i < zones.size() && is_running; i++) {
            FsaePlan.InputZone zone = zones.get(i);
            if(i > start_index && zone.select_first) {
                waitForUser("Next is " + plan.izName(zone.floor, zone.address) + ", which isn't right below the last zone. "
                    + "Select it in the configurator, then press Resume.");
            }
            else {
                safePoint();
            }
            if(!is_running) {
                return;
            }

            report(i, zones.size(), "Input zone: " + zone.describe());
            for(int attempt = 1; is_running; attempt++) {
                bot.beginStep();
                enterInputZone(zone);
                if(!bot.consumeAborted()) {
                    break;
                }
                if(attempt >= MAX_REDOS) {
                    stopWith("Interrupted " + attempt + " times on " + plan.izName(zone.floor, zone.address)
                        + ". Select it in the configurator, then press Start to redo it.");
                    return;
                }
                //Down is the last key, so the cursor is still on this zone: cancel the edit and enter it again
                System.out.println("Redoing " + plan.izName(zone.floor, zone.address));
                recover(KeyEvent.VK_ESCAPE, KeyEvent.VK_ESCAPE);
            }
        }
    }

    //Columns: Tag (Line1), Tag (Line2), Type, ... Enter moves on to the next column in edit mode.
    private void enterInputZone(FsaePlan.InputZone zone) throws InterruptedException {
        settle();
        bot.pressKey(KeyEvent.VK_ENTER, 1);
        bot.pasteText(zone.tag1);
        bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
        if(!plan.iz_tag2.isBlank()) {
            bot.pasteText(zone.tag2);
        }
        bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
        bot.pressKey(zone.type.key, 1);
        bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH * 3);
        bot.pressKey(KeyEvent.VK_ESCAPE, 1);
        bot.pressKey(KeyEvent.VK_DOWN);
    }

    // ---------------------------------------------------------------- step 2: logic

    private void runLogic() throws InterruptedException {
        List<FsaePlan.Logic> logic = plan.logic();

        for(int i = start_index; i < logic.size() && is_running; i++) {
            FsaePlan.Logic l = logic.get(i);
            report(i, logic.size(), "Logic: " + l.describe());
            String ask = "Open Advanced Logic for " + plan.izName(l.floor, l.address) + " (" + l.floor.floor.name
                + "), click into the Equation box, then press Enter logic (or F8).";

            for(int attempt = 1; is_running; attempt++) {
                waitForUser(ask);
                if(!is_running) {
                    return;
                }

                bot.beginStep();
                enterLogic(l);
                if(!bot.consumeAborted()) {
                    System.out.println("Logic set for " + l.name);
                    break;
                }
                if(attempt >= MAX_REDOS) {
                    stopWith("Interrupted " + attempt + " times on " + l.name + ". Press Start to try this floor again.");
                    return;
                }
                ask = "Entering " + l.name + " was interrupted. Click back into its Equation box, then press Enter logic to redo it.";
            }
        }
    }

    private void enterLogic(FsaePlan.Logic l) {
        bot.clearText();
        bot.pasteText(l.equation);
        bot.pressKey(KeyEvent.VK_TAB, 1, TAB_DELAY_STRENGTH);

        bot.clearText();
        bot.pasteText(l.name);
        bot.pressKey(KeyEvent.VK_TAB, 1, TAB_DELAY_STRENGTH);

        bot.clearText();
        bot.pasteText(l.comment);
    }

    // ---------------------------------------------------------------- step 3: graphic adder

    private void runAdder() throws InterruptedException {
        List<FsaePlan.AdderEntry> entries = plan.adder();

        for(int i = start_index; i < entries.size() && is_running; i++) {
            FsaePlan.AdderEntry e = entries.get(i);
            if(i > start_index && e.new_adder) {
                waitForUser("Graphic adder " + (e.adder - 1) + " is full. Go to the next graphic adder, select its first row, then press Resume.");
            }
            else {
                safePoint();
            }
            if(!is_running) {
                return;
            }

            report(i, entries.size(), "Graphic adder " + e.describe());
            bot.beginStep();
            enterAdder(e);
            if(bot.consumeAborted()) {
                //Changing Type/Assignment sends the selection back to the top, so this row can't be found again safely
                recover(KeyEvent.VK_ESCAPE, KeyEvent.VK_ESCAPE);
                stopWith("Interrupted on adder " + e.adder + " row " + e.row + " (" + e.tag + "). Select that row in the configurator, "
                    + "check it, then press Start to continue from it.");
                return;
            }

            //Selection restarts from the top after a Type/Assignment change: move down to the next row.
            //A full adder isn't scrolled - the user moves to the next adder instead.
            boolean last = i == entries.size() - 1;
            if(!last && !entries.get(i + 1).new_adder) {
                bot.pressKey(KeyEvent.VK_DOWN, e.row + 1, SCROLL_DELAY_STRENGTH);
            }
        }
    }

    //Columns: Type, Assignment, Tag, ... (FX4000 has a Tag2 after Tag)
    private void enterAdder(FsaePlan.AdderEntry e) throws InterruptedException {
        settle();

        //Type: Ipt Zone
        bot.pressKey(KeyEvent.VK_ENTER, 1);
        bot.pressKey(KeyEvent.VK_I, 1);
        bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);

        //Assignment
        bot.pressKey(e.type.key, 1);
        bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);

        //Tag
        bot.pasteText(e.tag);
        bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
        if(plan.panel.adder_tag2) {
            bot.pressKey(KeyEvent.VK_ENTER);
        }
    }

    // ---------------------------------------------------------------- run control

    private void report(int index, int total, String text) {
        System.out.println(text);
        if(listener != null) {
            listener.onProgress(step, index, total);
        }
    }

    //Pause until the user presses Resume, telling them what to do first
    private void waitForUser(String reason) throws InterruptedException {
        pause_requested = false;
        System.out.println(reason);
        setPaused(true, reason);
        while(is_paused && is_running) {
            Thread.sleep(50);
        }
    }

    //Honour the Pause button between entries, never partway through one
    private void safePoint() throws InterruptedException {
        if(pause_requested && is_running) {
            waitForUser("Paused. Don't change the selected row in the configurator, then press Resume.");
        }
    }

    private void recover(int... keys) {
        bot.beginStep();
        for(int key : keys) {
            bot.pressKey(key);
        }
        bot.consumeAborted();
    }

    private void stopWith(String message) {
        System.out.println(message);
        finish_message = message;
        setIsRunning(false);
    }

    private void settle() throws InterruptedException {
        if(WAIT_FOR_READY) {
            bot.settle(DELAY);
        }
        else {
            Thread.sleep(DELAY);
        }
    }

    public void requestPause() {
        pause_requested = true;
    }

    public boolean isPauseRequested() {
        return pause_requested;
    }

    public void resumeRun() {
        setPaused(false, null);
    }

    public void setIsRunning(boolean status) {
        is_running = status;
        if(!status) {
            is_paused = false;
            bot.stop();
        }
    }

    public boolean getIsRunning() {
        return is_running;
    }

    private void setPaused(boolean status, String reason) {
        boolean changed = is_paused != status;
        is_paused = status;
        if(changed && listener != null) {
            listener.onPausedChanged(status, reason);
        }
    }

    //Used by DataEntryBot's ready check (configurator busy or lost focus); the reason is in the log
    @Override
    public void setIsPaused(boolean status) {
        setPaused(status, null);
    }

    @Override
    public boolean getIsPaused() {
        return is_paused;
    }

    private void readSettings() {
        try {
            File file = new File("settings.ini");
            if(!file.exists()) {
                return;
            }
            Ini ini = new Ini(file);
            DELAY = valueOr(ini.get("Key Delay", "delayTime", Integer.class), DELAY);
            DEVICE_UPDATE_DELAY_STRENGTH = valueOr(ini.get("Key Delay", "deviceUpdateDelayStrength", Double.class), DEVICE_UPDATE_DELAY_STRENGTH);
            WAIT_FOR_READY = valueOr(ini.get("Ready Check", "waitForReady", Boolean.class), WAIT_FOR_READY);
            READY_TIMEOUT = valueOr(ini.get("Ready Check", "readyTimeout", Integer.class), READY_TIMEOUT);
        } catch (Exception e) {
            System.out.println("Could not read settings.ini, using default delays: " + e.getMessage());
        }
    }

    private static <T> T valueOr(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
