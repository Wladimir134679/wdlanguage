package ru.wds.wdl.idea.debug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.eclipse.lsp4j.debug.StackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Что видно, пока стоим: потоки и их кадры.
 * <p>
 * Потоков у скрипта бывает много ({@code th.spawn}), и панель показывает их все —
 * но кадры <b>того самого</b> потока, который встал, известны сразу: их спросили
 * в момент остановки, пока он точно стоит. Остальные потоки достаются по требованию:
 * человек редко открывает соседний, а спрашивать их все на каждом шаге — это столько
 * же запросов к адаптеру, сколько потоков в скрипте.
 */
final class WdlSuspendContext extends XSuspendContext {

    private final WdlExecutionStack active;
    private final WdlExecutionStack[] stacks;

    WdlSuspendContext(DapSession dap, List<org.eclipse.lsp4j.debug.Thread> threads,
                      int stoppedThread, List<StackFrame> known) {
        List<WdlExecutionStack> all = new ArrayList<>(threads.size());
        WdlExecutionStack stopped = null;
        for (org.eclipse.lsp4j.debug.Thread thread : threads) {
            boolean current = thread.getId() == stoppedThread;
            WdlExecutionStack stack = new WdlExecutionStack(dap, thread, current ? known : null,
                    thread.getName());
            if (current) {
                stopped = stack;
            }
            all.add(stack);
        }
        if (stopped == null) {
            // Адаптер назвал поток, которого нет в списке: он мог умереть между двумя
            // ответами. Кадры при этом уже получены, и показать их честнее, чем ничего.
            org.eclipse.lsp4j.debug.Thread ghost = new org.eclipse.lsp4j.debug.Thread();
            ghost.setId(stoppedThread);
            ghost.setName("поток " + stoppedThread);
            stopped = new WdlExecutionStack(dap, ghost, known, ghost.getName());
            all.add(stopped);
        }
        this.active = stopped;
        this.stacks = all.toArray(new WdlExecutionStack[0]);
    }

    @Override
    public @Nullable XExecutionStack getActiveExecutionStack() {
        return active;
    }

    @Override
    public XExecutionStack @NotNull [] getExecutionStacks() {
        return stacks;
    }

    /** Номер потока в понятиях протокола: им адресуются шаги и возобновление. */
    int activeThread() {
        return active.threadId();
    }

    /** Кадры одного потока. */
    static final class WdlExecutionStack extends XExecutionStack {

        private final DapSession dap;
        private final int threadId;
        private final List<StackFrame> known;

        WdlExecutionStack(DapSession dap, org.eclipse.lsp4j.debug.Thread thread,
                          @Nullable List<StackFrame> known, String name) {
            super(name, AllIcons.Debugger.ThreadSuspended);
            this.dap = dap;
            this.threadId = thread.getId();
            this.known = known;
        }

        int threadId() {
            return threadId;
        }

        /**
         * Верхний кадр — тот, что подсвечивается в редакторе сразу после остановки.
         * <p>
         * Спрашивается платформой из потока событий, поэтому здесь только уже готовое:
         * у потока, который встал, кадры есть, у соседнего — {@code null}, и платформа
         * попросит их через {@link #computeStackFrames}.
         */
        @Override
        public @Nullable XStackFrame getTopFrame() {
            return known == null || known.isEmpty() ? null : new WdlStackFrame(dap, known.get(0));
        }

        @Override
        public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    List<StackFrame> frames = known != null ? known : dap.stackTrace(threadId);
                    List<XStackFrame> shown = new ArrayList<>();
                    for (int index = firstFrameIndex; index < frames.size(); index++) {
                        shown.add(new WdlStackFrame(dap, frames.get(index)));
                    }
                    container.addStackFrames(shown, true);
                } catch (DapFailure failed) {
                    container.errorOccurred(failed.getMessage());
                }
            });
        }
    }
}
