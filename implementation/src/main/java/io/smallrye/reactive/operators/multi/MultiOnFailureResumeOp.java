
package io.smallrye.reactive.operators.multi;

import io.smallrye.reactive.CompositeException;
import io.smallrye.reactive.Multi;
import io.smallrye.reactive.helpers.ParameterValidation;
import io.smallrye.reactive.subscription.SwitchableSubscriptionSubscriber;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.function.Function;

public class MultiOnFailureResumeOp<T> extends AbstractMultiWithUpstream<T, T> {

    private final Function<? super Throwable, ? extends Publisher<? extends T>> next;

    public MultiOnFailureResumeOp(Multi<? extends T> upstream,
            Function<? super Throwable, ? extends Publisher<? extends T>> next) {
        super(upstream);
        this.next = ParameterValidation.nonNull(next, "next");
    }

    @Override
    public void subscribe(Subscriber<? super T> downstream) {
        upstream.subscribe(new ResumeSubscriber<>(downstream, next));
    }

    static final class ResumeSubscriber<T> extends SwitchableSubscriptionSubscriber<T> {

        private final Function<? super Throwable, ? extends Publisher<? extends T>> next;

        private boolean switched;

        ResumeSubscriber(Subscriber<? super T> downstream,
                Function<? super Throwable, ? extends Publisher<? extends T>> next) {
            super(downstream);
            this.next = next;
        }

        @Override
        public void onSubscribe(Subscription su) {
            if (!switched) {
                downstream.onSubscribe(this);
            }
            super.setOrSwitchUpstream(su);
        }

        @Override
        public void onNext(T item) {
            downstream.onNext(item);

            if (!switched) {
                emitted(1);
            }
        }

        @Override
        public void onError(Throwable failure) {
            if (!switched) {
                switched = true;
                Publisher<? extends T> publisher;
                try {
                    publisher = next.apply(failure);
                    if (publisher == null) {
                        throw new NullPointerException(ParameterValidation.SUPPLIER_PRODUCED_NULL);
                    }
                } catch (Throwable e) {
                    CompositeException exception = new CompositeException(failure, e);
                    super.onError(exception);
                    return;
                }
                publisher.subscribe(this);
            } else {
                super.onError(failure);
            }
        }

    }
}