package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InMemoryChannelOutboundFrameHandoff implements ChannelOutboundFrameHandoff {
    private static final Logger log = LoggerFactory.getLogger(InMemoryChannelOutboundFrameHandoff.class);

    private final Map<String, QueueState> queues = new ConcurrentHashMap<>();
    private final List<FrameAvailableListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public boolean offer(
        ChannelOutboundProfileConsumer consumer,
        ChannelOutboundFrame frame,
        int maxPendingFinals,
        int transientCapacity
    ) {
        String key = ProfileConsumerKeys.key(consumer);
        QueueState state = queues.computeIfAbsent(key, ignored -> new QueueState());
        synchronized (state) {
            if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                if (state.pendingFinals >= maxPendingFinals) {
                    return false;
                }
                state.frames.addLast(frame);
                state.pendingFinals += 1;
                state.notifyAll();
            } else {
                int maxTotalFrames = maxPendingFinals + Math.max(0, transientCapacity);
                while (state.frames.size() >= maxTotalFrames && dropFirstTransient(state)) {
                    // Keep room for current transient without discarding pending finals.
                }
                if (state.frames.size() >= maxTotalFrames) {
                    return false;
                }
                state.frames.addLast(frame);
                state.notifyAll();
            }
        }
        notifyFrameAvailable(consumer);
        return true;
    }

    @Override
    public int pendingFinals(ChannelOutboundProfileConsumer consumer) {
        QueueState state = queues.get(ProfileConsumerKeys.key(consumer));
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.pendingFinals;
        }
    }

    @Override
    public List<ChannelOutboundFrame> drain(ChannelOutboundProfileConsumer consumer, int limit) {
        String key = ProfileConsumerKeys.key(consumer);
        QueueState state = queues.get(key);
        if (state == null || limit <= 0) {
            return List.of();
        }
        synchronized (state) {
            List<ChannelOutboundFrame> drained = drainAvailable(state, limit);
            return List.copyOf(drained);
        }
    }

    @Override
    public List<ChannelOutboundFrame> drainOrWait(
        ChannelOutboundProfileConsumer consumer,
        int limit,
        Duration timeout
    ) throws InterruptedException {
        if (limit <= 0) {
            return List.of();
        }
        String key = ProfileConsumerKeys.key(consumer);
        QueueState state = queues.computeIfAbsent(key, ignored -> new QueueState());
        synchronized (state) {
            if (state.frames.isEmpty()) {
                long waitMillis = waitMillis(timeout);
                if (waitMillis > 0) {
                    state.wait(waitMillis);
                }
            }
            List<ChannelOutboundFrame> drained = drainAvailable(state, limit);
            return List.copyOf(drained);
        }
    }

    @Override
    public boolean hasFrames(ChannelOutboundProfileConsumer consumer) {
        QueueState state = queues.get(ProfileConsumerKeys.key(consumer));
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return !state.frames.isEmpty();
        }
    }

    @Override
    public void requeueFirst(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrame frame) {
        String key = ProfileConsumerKeys.key(consumer);
        QueueState state = queues.computeIfAbsent(key, ignored -> new QueueState());
        synchronized (state) {
            state.frames.addFirst(frame);
            if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                state.pendingFinals += 1;
            }
            state.notifyAll();
        }
        notifyFrameAvailable(consumer);
    }

    @Override
    public void registerListener(FrameAvailableListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void unregisterListener(FrameAvailableListener listener) {
        listeners.remove(listener);
    }

    private void notifyFrameAvailable(ChannelOutboundProfileConsumer consumer) {
        for (FrameAvailableListener listener : listeners) {
            try {
                listener.framesAvailable(consumer);
            } catch (RuntimeException error) {
                log.warn("channel outbound handoff listener failed consumer={}", ProfileConsumerKeys.key(consumer), error);
            }
        }
    }

    private static List<ChannelOutboundFrame> drainAvailable(QueueState state, int limit) {
        List<ChannelOutboundFrame> drained = new ArrayList<>();
        while (!state.frames.isEmpty() && drained.size() < limit) {
            ChannelOutboundFrame frame = state.frames.removeFirst();
            if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                state.pendingFinals -= 1;
            }
            drained.add(frame);
        }
        return drained;
    }

    private static long waitMillis(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return 0;
        }
        long millis = timeout.toMillis();
        return millis <= 0 ? 1 : millis;
    }

    private static boolean dropFirstTransient(QueueState state) {
        Iterator<ChannelOutboundFrame> iterator = state.frames.iterator();
        while (iterator.hasNext()) {
            ChannelOutboundFrame frame = iterator.next();
            if (frame.kind() != ChannelOutboundFrameKind.FINAL_DELIVERY) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static final class QueueState {
        private final ArrayDeque<ChannelOutboundFrame> frames = new ArrayDeque<>();
        private int pendingFinals;
    }
}
