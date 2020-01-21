/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.client;

import com.google.gwt.core.client.impl.SchedulerImpl;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.SimpleEventBus;
import com.google.web.bindery.event.shared.Event;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.vaadin.client.TrackingScheduler.TrackingSchedulerEmptyEvent.Handler;

/**
 * Scheduler implementation which tracks and reports whether there is any work
 * queued or currently being executed.
 *
 * @author Vaadin Ltd
 * @since 1.0
 */
public class TrackingScheduler extends SchedulerImpl {

    private EventBus eventBus = new SimpleEventBus();

    /**
     * Keeps track of if there are deferred commands that are being executed. 0
     * == no deferred commands currently in progress, > 0 otherwise.
     */
    private int deferredCommandTrackers = 0;

    @Override
    public void scheduleDeferred(ScheduledCommand cmd) {
        deferredCommandTrackers++;
        super.scheduleDeferred(cmd);
        super.scheduleDeferred(
                this::decrementDeferredTrackerAndNotifyHandlersIfNeeded);
    }

    private void decrementDeferredTrackerAndNotifyHandlersIfNeeded() {
        deferredCommandTrackers--;
        if (deferredCommandTrackers < 1) {
            eventBus.fireEvent(new TrackingSchedulerEmptyEvent());
        }
    }

    /**
     * Adds a handler that will be fired if the deferred queue becomes empty.
     * 
     * @param handler
     *            - the handler to register
     * @return the registration for the handler
     */
    public HandlerRegistration addEmptyQueueListener(Handler handler) {
        return eventBus.addHandler(TrackingSchedulerEmptyEvent.getType(),
                handler);
    }

    /**
     * Checks if there is work queued or currently being executed.
     *
     * @return true if there is work queued or if work is currently being
     *         executed, false otherwise
     */
    public boolean hasWorkQueued() {
        return deferredCommandTrackers != 0;
    }

    /**
     * Event that is fired when the {@link TrackingScheduler} becomes empty,
     * i.e. all deferred tasks are depleated from the queue.
     */
    public static class TrackingSchedulerEmptyEvent
            extends Event<TrackingSchedulerEmptyEvent.Handler> {

        /**
         * Handler interface for observing {@link TrackingSchedulerEmptyEvent}
         * events.
         */
        public interface Handler extends EventHandler {

            /**
             * Invoked when the {@link TrackingSchedulerEmptyEvent} is fired
             * from {@link TrackingScheduler}.
             */
            void onQueueEmpty();
        }

        private static Type<Handler> type = null;

        /**
         * Gets the type of the event after ensuring the type has been created.
         *
         * @return the type for the event
         */
        public static Type<Handler> getType() {
            if (type == null) {
                type = new Type<>();
            }
            return type;
        }

        @Override
        public Type<Handler> getAssociatedType() {
            return type;
        }

        @Override
        protected void dispatch(Handler handler) {
            handler.onQueueEmpty();
        }
    }
}
