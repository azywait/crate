/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.support;

import com.google.common.collect.Iterables;
import io.crate.concurrent.CompletableFutures;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class ThreadPools {

    public static IntSupplier numIdleThreads(ThreadPoolExecutor executor) {
        return () -> Math.max(executor.getMaximumPoolSize() - executor.getActiveCount(), 1);
    }

    public static Executor fallbackOnRejection(Executor executor) {
        return new DirectFallbackExecutor(executor);
    }

    /**
     * runs each runnable of the runnableCollection in it's own thread unless there aren't enough threads available.
     * In that case it will partition the runnableCollection to match the number of available threads.
     *
     * @param executor           executor that is used to execute the callableList
     * @param availableThreads   A function returning the number of threads which can be utilized
     * @param suppliers          a collection of callable that should be executed
     * @param mergeFunction      function that will be applied to merge the results of multiple callable in case that they are
     *                           executed together if the threadPool is exhausted
     * @param <T>                type of the final result
     * @return a future that will return a list of the results of the callableList
     * @throws RejectedExecutionException in case all threads are busy and overloaded.
     */
    public static <T> CompletableFuture<List<T>> runWithAvailableThreads(
        Executor executor,
        IntSupplier availableThreads,
        Collection<Supplier<T>> suppliers,
        final Function<List<T>, T> mergeFunction) throws RejectedExecutionException {

        ArrayList<CompletableFuture<T>> futures;
        int threadsToUse = availableThreads.getAsInt();
        if (threadsToUse < suppliers.size()) {
            Iterable<List<Supplier<T>>> partition = Iterables.partition(suppliers, suppliers.size() / threadsToUse);

            futures = new ArrayList<>(threadsToUse + 1);
            for (final List<Supplier<T>> callableList : partition) {
                CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                    ArrayList<T> results = new ArrayList<>(callableList.size());
                    for (Supplier<T> supplier : callableList) {
                        results.add(supplier.get());
                    }
                    return mergeFunction.apply(results);
                }, executor);
                futures.add(future);
            }
        } else {
            futures = new ArrayList<>(suppliers.size());
            for (Supplier<T> supplier : suppliers) {
                futures.add(CompletableFuture.supplyAsync(supplier, executor));
            }
        }
        return CompletableFutures.allAsList(futures);
    }

    /**
     * Executor that delegates to {@link Executor} or
     * runs the command synchronous if the provided executor throws {@link EsRejectedExecutionException}
     */
    private static class DirectFallbackExecutor implements Executor {

        private final Executor delegate;

        DirectFallbackExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(@Nonnull Runnable command) {
            try {
                delegate.execute(command);
            } catch (RejectedExecutionException | EsRejectedExecutionException e) {
                command.run();
            }
        }
    }
}
