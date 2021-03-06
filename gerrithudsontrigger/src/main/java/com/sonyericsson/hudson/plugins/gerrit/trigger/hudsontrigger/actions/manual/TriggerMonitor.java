/*
 * The MIT License
 *
 * Copyright 2010 Sony Ericsson Mobile Communications. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.actions.manual;

import com.sonyericsson.hudson.plugins.gerrit.gerritevents.dto.events.PatchsetCreated;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.dto.events.lifecycle.GerritEventLifecycleListener;
import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.TriggeredItemEntity;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BallColor;
import hudson.model.Result;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Keeps track of the lifecycle of a GerritEvent.
 * @author Robert Sandell &lt;robert.sandell@sonyericsson.com&gt;
 */
public class TriggerMonitor implements GerritEventLifecycleListener {

    private List<EventState> events = new LinkedList<EventState>();

    /**
     * Adds the event and a holder for its state to the list of triggered events.
     * And adds this TriggerMonitor as a listener to the event.
     * Unless it doesn't already exists in the list of events.
     * @param event the event.
     */
    public synchronized void add(PatchsetCreated event) {
        if (!contains(event)) {
            event.addListener(this);
            events.add(new EventState(event));
        }
    }

    /**
     * Checks to see if the list of triggered events and their states contains the given event.
     * @param event the event to check.
     * @return true if it exests in the list.
     * @see #getEvents()
     */
    public synchronized boolean contains(PatchsetCreated event) {
        for (EventState state : events) {
            if (state.event != null && state.event.equals(event)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the EventState containing the given event.
     * @param event the event.
     * @return the state, or null if there is none.
     */
    private synchronized EventState findState(PatchsetCreated event) {
        for (EventState state : events) {
            if (state.event != null && state.event.equals(event)) {
                return state;
            }
        }
        return null;
    }

    @Override
    public synchronized void triggerScanStarting(PatchsetCreated event) {
        EventState state = findState(event);
        if (state != null) {
            state.triggerScanStarted = true;
        }
    }

    @Override
    public synchronized void triggerScanDone(PatchsetCreated event) {
        EventState state = findState(event);
        if (state != null) {
            state.triggerScanDone = true;
        }
    }

    @Override
    public synchronized void projectTriggered(PatchsetCreated event, AbstractProject project) {
        EventState state = findState(event);
        if (state != null) {
            state.addProject(project);
        }
    }

    @Override
    public synchronized void buildStarted(PatchsetCreated event, AbstractBuild build) {
        EventState state = findState(event);
        if (state != null) {
            state.setBuild(build);
        }
    }

    @Override
    public synchronized void buildCompleted(PatchsetCreated event, AbstractBuild build) {
        EventState state = findState(event);
        if (state != null) {
            if (state.allBuildsCompleted && state.isReallyAllBuildsCompleted()) {
                event.removeListener(this);
            }
        }
    }

    @Override
    public synchronized void allBuildsCompleted(PatchsetCreated event) {
        EventState state = findState(event);
        if (state != null) {
            state.allBuildsCompleted = true;
            if (state.isReallyAllBuildsCompleted()) {
                event.removeListener(this);
            }
        }
    }

    /**
     * The list of events and their states.
     * @return a list.
     */
    public synchronized List<EventState> getEvents() {
        return events;
    }

    /**
     * An iterator of the list of events and their states.
     * @return a iterator.
     */
    @SuppressWarnings("unused") //called from Jelly
    public synchronized Iterator<EventState> getEventsIterator() {
        return events.iterator();
    }

    /**
     * State information about an event.
     */
    public static class EventState {
        private PatchsetCreated event;
        private boolean triggerScanStarted = false;
        private boolean triggerScanDone = false;
        private boolean allBuildsCompleted = false;
        private List<TriggeredItemEntity> builds;

        /**
         * Standard constructor.
         * @param event the event to track.
         */
        EventState(PatchsetCreated event) {
            this.event = event;
            builds = new LinkedList<TriggeredItemEntity>();
        }

        /**
         * Adds a project to the list of triggered projects.
         * @param project the project.
         */
        void addProject(AbstractProject project) {
            builds.add(new TriggeredItemEntity(project));
        }

        /**
         * Sets the started build to an already triggered project.
         * @param build the build.
         */
        void setBuild(AbstractBuild build) {
            for (TriggeredItemEntity entity : builds) {
                if (entity.equals(build.getProject())) {
                    entity.setBuild(build);
                }
            }
        }

        /**
         * Returns the appropriate ball color for the current status of this event and its builds.
         * @return the path to the image of the ball.
         * @see hudson.model.Result#color
         * @see hudson.model.BallColor#getImage()
         */
        @SuppressWarnings("unused") //called from jelly
        public String getBallColor() {
            if (!triggerScanStarted) {
                return BallColor.GREY.getImage();
            } else if (!triggerScanDone) {
                return BallColor.GREY_ANIME.getImage();
            } else if (isUnTriggered()) {
                return BallColor.DISABLED.getImage();
            } else {
                Result result = getLeastFavorableResult();
                if (result != null) {
                    return result.color.getImage();
                } else {
                    return BallColor.GREY_ANIME.getImage();
                }
            }
        }

        /**
         * Gives the least favorable {@link hudson.model.Run#getResult()} in the list of build, if there is any results.
         * @return the result or null if there is none.
         */
        private Result getLeastFavorableResult() {
            Result least = null;
            for (TriggeredItemEntity entity : builds) {
                if (entity.getBuild() != null && entity.getBuild().getResult() != null) {
                    Result tmp = entity.getBuild().getResult();
                    if (least == null) {
                        least = tmp;
                    } else {
                        least = least.combine(tmp);
                    }
                }
            }
            return least;
        }

        /**
         * If no active triggers where interested in this event.
         * Determined by: {@link #isTriggerScanDone()} && {@link #getBuilds()}.size() <= 0
         * unless not {@link #isTriggerScanStarted()}.
         * @return true if so.
         */
        public boolean isUnTriggered() {
            if (!triggerScanStarted) {
                return false;
            } else {
                return triggerScanDone && builds.size() <= 0;
            }
        }

        /**
         * The event.
         * @return the event.
         */
        public PatchsetCreated getEvent() {
            return event;
        }

        /**
         * If the active triggers in the system has started to be notified.
         * @return true if so.
         */
        public boolean isTriggerScanStarted() {
            return triggerScanStarted;
        }

        /**
         * If all the active triggers in the system has been notified about this event.
         * @return true if so.
         */
        public boolean isTriggerScanDone() {
            return triggerScanDone;
        }

        /**
         * If all builds started by this event has completed.
         * @return true if so.
         */
        public boolean isAllBuildsCompleted() {
            return allBuildsCompleted;
        }

        /**
         * Gets the builds that has started for this event.
         * @return the builds.
         */
        public List<TriggeredItemEntity> getBuilds() {
            return builds;
        }

        /**
         * Goes through the list of builds and checks if anyone is still building.
         * Even though the event {@link TriggerMonitor#allBuildsCompleted(PatchsetCreated)}
         * has been called that only applies to non silent builds, an extra check is needed.
         * @return true if all builds has completed.
         */
        public boolean isReallyAllBuildsCompleted() {
            for (TriggeredItemEntity entity : builds) {
                if (entity.getBuild() == null || !entity.getBuild().isLogUpdated()) {
                    return false;
                }
            }
            return true;
        }
    }
}
