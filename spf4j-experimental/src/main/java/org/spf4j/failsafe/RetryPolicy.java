/*
 * Copyright (c) 2001-2017, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 * Additionally licensed with:
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spf4j.failsafe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.CheckReturnValue;
import javax.annotation.ParametersAreNonnullByDefault;
import org.spf4j.base.ExecutionContexts;
import org.spf4j.failsafe.concurrent.DefaultContextAwareRetryExecutor;
import org.spf4j.failsafe.concurrent.RetryExecutor;

/**
 * @author Zoltan Farkas
 */
@ParametersAreNonnullByDefault
public class RetryPolicy<T, C extends Callable<T>> {

  private final Supplier<RetryPredicate<T, C>> retryPredicate;

  private final Supplier<RetryExecutor> execSupplier;

  private final int maxExceptionChain;

  public RetryPolicy(final Supplier<RetryPredicate<T, C>> retryPredicate,
          final Supplier<RetryExecutor> execSupplier,
          final int maxExceptionChain) {
    this.retryPredicate = retryPredicate;
    this.maxExceptionChain = maxExceptionChain;
    this.execSupplier = execSupplier;
  }

  public final <EX extends Exception> T call(final C pwhat, final Class<EX> exceptionClass)
          throws InterruptedException, TimeoutException, EX {
    return Retry.call(pwhat, getRetryPredicate(), exceptionClass, maxExceptionChain);
  }

  public final Future<T> submit(final C pwhat) {
    return execSupplier.get().submit(pwhat, this);
  }

  public RetryPredicate<T, C> getRetryPredicate() {
    return retryPredicate.get();
  }

  public static final class Builder<T, C extends Callable<T>> {

    private static final int MAX_EX_CHAIN_DEFAULT = Integer.getInteger("spf4j.failsafe.defaultMaxExceptionChain", 10);

    private static final long DEFAULT_MAX_DELAY_NANOS =
            TimeUnit.MILLISECONDS.toNanos(Long.getLong("spf4j.failsafe.defaultMaxRetryDelayMillis", 5000));

    private static final long DEFAULT_INITIAL_DELAY_NANOS =
            Long.getLong("spf4j.failsafe.defaultInitialRetryDelayNanos", 1000);

    private static final int DEFAULT_INITIAL_NODELAY_RETRIES =
            Integer.getInteger("spf4j.failsafe.defaultInitialNoDelayRetries", 3);

    public final class PredicateBuilder {

      private final List<Supplier<? extends PartialRetryPredicate<T, C>>> predicates = new ArrayList<>(2);

      private Consumer<Supplier<RetryPredicate<T, C>>> consumer;

      private int nrInitialRetries;

      private long startDelayNanos;

      private long maxDelayNanos;

      private double jitterFactor;

      private PredicateBuilder(final Consumer<Supplier<RetryPredicate<T, C>>> consumer) {
        this.consumer = consumer;
        this.nrInitialRetries = DEFAULT_INITIAL_NODELAY_RETRIES;
        this.startDelayNanos = DEFAULT_INITIAL_DELAY_NANOS;
        this.maxDelayNanos = DEFAULT_MAX_DELAY_NANOS;
        this.jitterFactor = 0.2;
      }

      @CheckReturnValue
      public PredicateBuilder withRetryOnException(final Class<? extends Exception> clasz) {
        return PredicateBuilder.this.withExceptionPartialPredicate((e, c)
                -> clasz.isAssignableFrom(e.getClass())
                ? RetryDecision.retryDefault(c) : null);
      }

      @CheckReturnValue
      public PredicateBuilder withRetryOnException(final Class<? extends Exception> clasz, final int maxRetries) {
        return withExceptionPartialPredicate((e, c)
                -> clasz.isAssignableFrom(e.getClass())
                ? RetryDecision.retryDefault(c) : null, maxRetries);
      }

      @CheckReturnValue
      public PredicateBuilder withExceptionPartialPredicate(
              final PartialExceptionRetryPredicate<T, C> predicate) {
        predicates.add(() -> predicate);
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withExceptionPartialPredicate(
              final PartialExceptionRetryPredicate<T, C> predicate,
              final int maxRetries) {
        predicates.add(() -> new CountLimitedPartialRetryPredicate<>(maxRetries, predicate));
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withExceptionStatefulPartialPredicate(
              final Supplier<PartialExceptionRetryPredicate<T, C>> predicateSupplier) {
        predicates.add(predicateSupplier);
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withResultPartialPredicate(
              final PartialResultRetryPredicate<T, C> predicate) {
        predicates.add(() -> predicate);
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withResultPartialPredicate(
              final PartialResultRetryPredicate<T, C> predicate,
              final int maxRetries) {
        predicates.add(() -> new CountLimitedPartialRetryPredicate<>(maxRetries, predicate));
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withResultStatefulPartialPredicate(
              final Supplier<PartialResultRetryPredicate<T, C>> predicateSupplier) {
        predicates.add(predicateSupplier);
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withJitterFactor(final double jitterfactor) {
        if (jitterFactor > 1 || jitterFactor < 0) {
          throw new IllegalArgumentException("Invalid jitter factor " + jitterfactor);
        }
        this.jitterFactor = jitterfactor;
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withInitialRetries(final int retries) {
        this.nrInitialRetries = retries;
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withInitialDelay(final long delay, final TimeUnit unit) {
        this.startDelayNanos = unit.toNanos(delay);
        return this;
      }

      @CheckReturnValue
      public PredicateBuilder withMaxDelay(final long delay, final TimeUnit unit) {
        this.maxDelayNanos = unit.toNanos(delay);
        return this;
      }

      @CheckReturnValue
      public Builder<T, C> finishPredicate() {
        consumer.accept(() -> new DefaultRetryPredicate(() -> new TypeBasedRetryDelaySupplier<>(
                (x) -> new JitteredDelaySupplier(new FibonacciRetryDelaySupplier(nrInitialRetries,
                        startDelayNanos, maxDelayNanos), jitterFactor)),
                predicates.toArray(new Supplier[predicates.size()])));
        return Builder.this;
      }

    }

    private Supplier<RetryPredicate<T, C>> retryPredicate = null;

    private int maxExceptionChain = MAX_EX_CHAIN_DEFAULT;

    private Supplier<RetryExecutor> execSupplier = () -> DefaultContextAwareRetryExecutor.instance();

    private DeadlineSupplier<C> deadlineSupplier = (c) -> ExecutionContexts.getContextDeadlineNanos();

    private Builder() {
    }

    public Builder<T, C> withMaxExceptionChain(final int maxExChain) {
      maxExceptionChain = maxExChain;
      return this;
    }

    public Builder<T, C> withExecutorService(final RetryExecutor es) {
      execSupplier = () -> es;
      return this;
    }

    public Builder<T, C> withDeadlineSupplier(final DeadlineSupplier<C> ds) {
      deadlineSupplier = ds;
      return this;
    }

    @CheckReturnValue
    public PredicateBuilder retryPredicateBuilder() {
      return new PredicateBuilder((x) -> retryPredicate = x);
    }

    @CheckReturnValue
    @SuppressWarnings("unchecked")
    public RetryPolicy<T, C> build() {
      Supplier<RetryPredicate<T, C>> rp = retryPredicate == null ? () -> RetryPredicate.NORETRY
              : retryPredicate;
        return new RetryPolicy<>(() -> new TimeoutRetryPredicate(rp.get(), deadlineSupplier),
                execSupplier,
                maxExceptionChain
      );
    }

  }

  @CheckReturnValue
  public static <T, C extends Callable<T>> Builder<T, C> newBuilder() {
    return new Builder<>();
  }

}
