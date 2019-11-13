package io.smallrye.reactive.helpers.queues;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.reactivestreams.Subscriber;

import io.smallrye.reactive.helpers.Subscriptions;

/**
 * Copy from Project Reactor.
 */
public class DrainUtils {

    /**
     * Indicates the source completed and the value field is ready to be emitted.
     * <p>
     * The AtomicLong (this) holds the requested amount in bits 0..62 so there is room
     * for one signal bit. This also means the standard request accounting helper method doesn't work.
     */
    static final long COMPLETED_MASK = 0x8000_0000_0000_0000L;
    static final long REQUESTED_MASK = 0x7FFF_FFFF_FFFF_FFFFL;

    private DrainUtils() {
        // avoid direct instantiation.
    }

    /**
     * Perform a potential post-completion request accounting.
     *
     * @param <T> the output value type
     * @param n the requested amount
     * @param downstream the downstream consumer
     * @param queue the queue holding the available values
     * @param requested the requested atomic long
     * @param isCancelled callback to detect cancellation
     * @return true if the state indicates a completion state.
     */
    public static <T> boolean postCompleteRequest(long n,
            Subscriber<? super T> downstream,
            Queue<T> queue,
            AtomicLong requested,
            BooleanSupplier isCancelled) {

        for (;;) {
            long r = requested.get();

            // extract the current request amount
            long r0 = r & REQUESTED_MASK;

            // preserve COMPLETED_MASK and calculate new requested amount
            long u = (r & COMPLETED_MASK) | Subscriptions.add(r0, n);

            if (requested.compareAndSet(r, u)) {
                // (complete, 0) -> (complete, n) transition then replay
                if (r == COMPLETED_MASK) {

                    postCompleteDrain(n | COMPLETED_MASK, downstream, queue, requested, isCancelled);

                    return true;
                }
                // (active, r) -> (active, r + n) transition then continue with requesting from upstream
                return false;
            }
        }

    }

    /**
     * Drains the queue either in a pre- or post-complete state.
     *
     * @param n the requested amount
     * @param downstream the downstream consumer
     * @param queue the queue holding available values
     * @param requested the atomic long keeping track of requests
     * @param isCancelled callback to detect cancellation
     * @return true if the queue was completely drained or the drain process was cancelled
     */
    static <T> boolean postCompleteDrain(long n,
            Subscriber<? super T> downstream,
            Queue<T> queue,
            AtomicLong requested,
            BooleanSupplier isCancelled) {

        long e = n & COMPLETED_MASK;

        System.out.println("postCompleteDrain");

        for (;;) {

            while (e != n) {
                if (isCancelled.getAsBoolean()) {
                    System.out.println("cancelled");
                    return true;
                }

                T t = queue.poll();

                if (t == null) {
                    downstream.onComplete();
                    return true;
                }

                downstream.onNext(t);
                e++;
            }

            if (isCancelled.getAsBoolean()) {
                return true;
            }

            if (queue.isEmpty()) {
                downstream.onComplete();
                return true;
            }

            n = requested.get();

            if (n == e) {

                n = requested.addAndGet(-(e & REQUESTED_MASK));

                if ((n & REQUESTED_MASK) == 0L) {
                    return false;
                }

                e = n & COMPLETED_MASK;
            }
        }

    }

    /**
     * Tries draining the queue if the source just completed.
     *
     * @param <T> the output value type
     * @param downstream the downstream consumer
     * @param queue the queue holding available values
     * @param requested the atomic long keeping track of requests
     * @param isCancelled callback to detect cancellation
     */
    public static <T> void postComplete(Subscriber<? super T> downstream,
            Queue<T> queue,
            AtomicLong requested,
            BooleanSupplier isCancelled) {

        System.out.println("post complete " + queue.isEmpty());
        if (queue.isEmpty()) {
            downstream.onComplete();
            return;
        }

        if (postCompleteDrain(requested.get(), downstream, queue, requested, isCancelled)) {
            System.out.println("drained");
            return;
        }

        for (;;) {
            long r = requested.get();

            if ((r & COMPLETED_MASK) != 0L) {
                return;
            }

            long u = r | COMPLETED_MASK;
            // (active, r) -> (complete, r) transition
            if (requested.compareAndSet(r, u)) {
                // if the requested amount was non-zero, drain the queue
                if (r != 0L) {
                    postCompleteDrain(u, downstream, queue, requested, isCancelled);
                }

                return;
            }
        }
    }

    /**
     * Perform a potential post-completion request accounting.
     *
     * @param <T> the output value type
     * @param n the request amount
     * @param downstream the downstream consumer
     * @param queue the queue of available values
     * @param requested the atomic long keeping track of requests
     * @param isCancelled callback to detect cancellation
     * @param error if not null, the error to signal after the queue has been drained
     * @return true if the state indicates a completion state.
     */
    public static <T> boolean postCompleteRequestDelayError(long n,
            Subscriber<? super T> downstream,
            Queue<T> queue,
            AtomicLong requested,
            BooleanSupplier isCancelled, Throwable error) {

        for (;;) {
            long r = requested.get();

            // extract the current request amount
            long r0 = r & REQUESTED_MASK;

            // preserve COMPLETED_MASK and calculate new requested amount
            long u = (r & COMPLETED_MASK) | Subscriptions.add(r0, n);

            if (requested.compareAndSet(r, u)) {
                // (complete, 0) -> (complete, n) transition then replay
                if (r == COMPLETED_MASK) {

                    postCompleteDrainDelayError(n | COMPLETED_MASK, downstream, queue, requested, isCancelled, error);

                    return true;
                }
                // (active, r) -> (active, r + n) transition then continue with requesting from upstream
                return false;
            }
        }

    }

    /**
     * Drains the queue either in a pre- or post-complete state, delaying an
     * optional error to the end of the drain operation.
     *
     * @param n the requested amount
     * @param downstream the downstream consumer
     * @param queue the queue holding available values
     * @param requested the atomic long keeping track of requests
     * @param isCancelled callback to detect cancellation
     * @param failure the delayed error
     * @return true if the queue was completely drained or the drain process was cancelled
     */
    static <T> boolean postCompleteDrainDelayError(long n,
            Subscriber<? super T> downstream,
            Queue<T> queue,
            AtomicLong requested,
            BooleanSupplier isCancelled,
            Throwable failure) {

        long e = n & COMPLETED_MASK;

        for (;;) {

            while (e != n) {
                if (isCancelled.getAsBoolean()) {
                    return true;
                }

                T t = queue.poll();

                if (t == null) {
                    if (failure == null) {
                        downstream.onComplete();
                    } else {
                        downstream.onError(failure);
                    }
                    return true;
                }

                downstream.onNext(t);
                e++;
            }

            if (isCancelled.getAsBoolean()) {
                return true;
            }

            if (queue.isEmpty()) {
                if (failure == null) {
                    downstream.onComplete();
                } else {
                    downstream.onError(failure);
                }
                return true;
            }

            n = requested.get();

            if (n == e) {

                n = requested.addAndGet(-(e & REQUESTED_MASK));

                if ((n & REQUESTED_MASK) == 0L) {
                    return false;
                }

                e = n & COMPLETED_MASK;
            }
        }

    }

    /**
     * Tries draining the queue if the source just completed.
     *
     * @param <T> the output value type
     * @param <F> the field type holding the requested amount
     * @param downstream the downstream consumer
     * @param queue the queue of available values
     * @param requested the atomic long keeping track of requests
     * @param instance the parent instance of the requested field
     * @param isCancelled callback to detect cancellation
     * @param failure if not null, the error to signal after the queue has been drained
     */
    public static <T, F> void postCompleteDelayError(Subscriber<? super T> downstream,
            Queue<T> queue,
            AtomicLong requested,
            F instance,
            BooleanSupplier isCancelled,
            Throwable failure) {

        if (queue.isEmpty()) {
            if (failure == null) {
                downstream.onComplete();
            } else {
                downstream.onError(failure);
            }
            return;
        }

        if (postCompleteDrainDelayError(requested.get(), downstream, queue, requested, isCancelled, failure)) {
            return;
        }

        for (;;) {
            long r = requested.get();

            if ((r & COMPLETED_MASK) != 0L) {
                return;
            }

            long u = r | COMPLETED_MASK;
            // (active, r) -> (complete, r) transition
            if (requested.compareAndSet(r, u)) {
                // if the requested amount was non-zero, drain the queue
                if (r != 0L) {
                    postCompleteDrainDelayError(u, downstream, queue, requested, isCancelled, failure);
                }

                return;
            }
        }
    }
}