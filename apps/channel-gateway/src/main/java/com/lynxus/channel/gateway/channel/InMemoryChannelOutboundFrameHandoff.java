package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class InMemoryChannelOutboundFrameHandoff implements ChannelOutboundFrameHandoff {
    private final Map<String, QueueState> queues = new ConcurrentHashMap<>();

    @Override
    public boolean offer(
        ChannelOutboundProfileConsumer consumer,
        ChannelOutboundFrame frame,
        int maxPendingFinals,
        int transientCapacity
    ) {
        QueueState state = queues.computeIfAbsent(ProfileConsumerKeys.key(consumer), ignored -> new QueueState());
        synchronized (state) {
            if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                if (state.pendingFinals >= maxPendingFinals) {
                    return false;
                }
                state.frames.addLast(frame);
                state.pendingFinals += 1;
                return true;
            }
            int maxTotalFrames = maxPendingFinals + Math.max(0, transientCapacity);
            while (state.frames.size() >= maxTotalFrames && dropFirstTransient(state)) {
                // Keep room for current transient without discarding pending finals.
            }
            if (state.frames.size() >= maxTotalFrames) {
                return false;
            }
            state.frames.addLast(frame);
            return true;
        }
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
        QueueState state = queues.get(ProfileConsumerKeys.key(consumer));
        if (state == null || limit <= 0) {
            return List.of();
        }
        synchronized (state) {
            List<ChannelOutboundFrame> drained = new ArrayList<>();
            while (!state.frames.isEmpty() && drained.size() < limit) {
                ChannelOutboundFrame frame = state.frames.removeFirst();
                if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                    state.pendingFinals -= 1;
                }
                drained.add(frame);
            }
            if (state.frames.isEmpty()) {
                queues.remove(ProfileConsumerKeys.key(consumer), state);
            }
            return List.copyOf(drained);
        }
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
