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
package org.spf4j.recyclable;

import com.google.common.annotations.Beta;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.ThreadSafe;
import org.spf4j.base.ExecutionContexts;
import org.spf4j.base.TimeSource;

/**
 * @author zoly
 */
@ThreadSafe
@ParametersAreNonnullByDefault
public interface SmartRecyclingSupplier<T> extends BlockingDisposable, Scanable<T> {

    /**
     * Borrow object from pool.
     * @param borower
     * @return
     * @throws InterruptedException
     * @throws TimeoutException
     * @throws ObjectCreationException
     */
    @Nonnull
    default T get(ObjectBorower borower) throws InterruptedException,
            TimeoutException, ObjectCreationException {
      return get(borower, ExecutionContexts.getTimeToDeadline(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }


    @Nonnull
    default T get(ObjectBorower borower, long timeout, TimeUnit unit)
            throws ObjectCreationException,
            InterruptedException, TimeoutException {
      T tryGet = tryGet(borower, timeout, unit);
      if (tryGet == null) {
        throw new TimeoutException("Timed out after " + timeout + " " + unit);
      } else {
        return tryGet;
      }
    }

    @Nullable
    default T tryGet(ObjectBorower borower, long timeout, TimeUnit unit) throws ObjectCreationException,
            InterruptedException {
      return tryGet(borower, TimeSource.nanoTime() + unit.toNanos(timeout));
    }


    @Nullable
    T tryGet(ObjectBorower borower, long deadlineNanos) throws ObjectCreationException,
            InterruptedException;


    /**
     * Return object to pool.
     * @param object
     * @param borower
     */
    void recycle(T object, ObjectBorower borower);


    /**
     * @return the object sample.
     */
    @Nonnull
    @Beta
    T getSample();

}
