/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.cep.extension;

import org.apache.log4j.Logger;
import org.wso2.siddhi.core.config.SiddhiContext;
import org.wso2.siddhi.core.event.StreamEvent;
import org.wso2.siddhi.core.event.in.InEvent;
import org.wso2.siddhi.core.event.in.InListEvent;
import org.wso2.siddhi.core.event.remove.RemoveEvent;
import org.wso2.siddhi.core.event.remove.RemoveListEvent;
import org.wso2.siddhi.core.persistence.ThreadBarrier;
import org.wso2.siddhi.core.query.QueryPostProcessingElement;
import org.wso2.siddhi.core.query.processor.window.RunnableWindowProcessor;
import org.wso2.siddhi.core.query.processor.window.WindowProcessor;
import org.wso2.siddhi.core.util.collection.queue.scheduler.ISchedulerSiddhiQueue;
import org.wso2.siddhi.core.util.collection.queue.scheduler.SchedulerSiddhiQueue;
import org.wso2.siddhi.core.util.collection.queue.scheduler.SchedulerSiddhiQueueGrid;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.Attribute.Type;
import org.wso2.siddhi.query.api.expression.Expression;
import org.wso2.siddhi.query.api.expression.Variable;
import org.wso2.siddhi.query.api.expression.constant.IntConstant;
import org.wso2.siddhi.query.api.expression.constant.LongConstant;
import org.wso2.siddhi.query.api.extension.annotation.SiddhiExtension;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@SiddhiExtension(namespace = "stratos", function = "gradient")
public class GradientFinderWindowProcessor extends WindowProcessor implements RunnableWindowProcessor {

    static final Logger log = Logger.getLogger(GradientFinderWindowProcessor.class);
    private ScheduledExecutorService eventRemoverScheduler;
    private ScheduledFuture<?> lastSchedule;
    private long timeToKeep;
    private int subjectedAttrIndex;
    private Attribute.Type subjectedAttrType;
    private List<InEvent> newEventList;
    private List<RemoveEvent> oldEventList;
    private ThreadBarrier threadBarrier;
    private ISchedulerSiddhiQueue<StreamEvent> window;

    @Override
    protected void processEvent(InEvent event) {
        acquireLock();
        try {
            newEventList.add(event);
        } finally {
            releaseLock();
        }
    }

    @Override
    protected void processEvent(InListEvent listEvent) {
        acquireLock();
        try {
            System.out.println(listEvent);
            for (int i = 0, size = listEvent.getActiveEvents(); i < size; i++) {
                newEventList.add((InEvent) listEvent.getEvent(i));
            }
        } finally {
            releaseLock();
        }
    }

    @Override
    public Iterator<StreamEvent> iterator() {
        return window.iterator();
    }

    @Override
    public Iterator<StreamEvent> iterator(String predicate) {
        if (siddhiContext.isDistributedProcessingEnabled()) {
            return ((SchedulerSiddhiQueueGrid<StreamEvent>) window).iterator(predicate);
        } else {
            return window.iterator();
        }
    }


    @Override
    public void run() {
        acquireLock();
        try {
            long scheduledTime = System.currentTimeMillis();
            try {
                oldEventList.clear();
                while (true) {
                    threadBarrier.pass();
                    RemoveEvent removeEvent = (RemoveEvent) window.poll();
                    if (removeEvent == null) {
                        if (oldEventList.size() > 0) {
                            nextProcessor.process(new RemoveListEvent(
                                    oldEventList.toArray(new RemoveEvent[oldEventList.size()])));
                            oldEventList.clear();
                        }

                        if (newEventList.size() > 0) {
                            InEvent[] inEvents = newEventList.toArray(new InEvent[newEventList.size()]);

                            InEvent[] gradientEvents = gradient(inEvents[0], inEvents[newEventList.size() - 1]);

                            for (InEvent inEvent : gradientEvents) {
                                window.put(new RemoveEvent(inEvent, -1));
                            }
                            nextProcessor.process(new InListEvent(gradientEvents));

                            newEventList.clear();
                        }

                        long diff = timeToKeep - (System.currentTimeMillis() - scheduledTime);
                        if (diff > 0) {
                            try {
                                if (lastSchedule != null) {
                                    lastSchedule.cancel(false);
                                }
                                lastSchedule = eventRemoverScheduler.schedule(this, diff, TimeUnit.MILLISECONDS);
                            } catch (RejectedExecutionException ex) {
                                log.warn("scheduling cannot be accepted for execution: elementID " +
                                        elementId);
                            }
                            break;
                        }
                        scheduledTime = System.currentTimeMillis();
                    } else {
                        oldEventList.add(new RemoveEvent(removeEvent, System.currentTimeMillis()));
                    }
                }
            } catch (Throwable t) {
                log.error(t.getMessage(), t);
            }
        } finally {
            releaseLock();
        }
    }


    /**
     * This function will calculate the linear gradient (per second) of the events received during
     * a specified time period.
     */
    private InEvent[] gradient(InEvent firstInEvent, InEvent lastInEvent) {
        double firstVal = 0.0, lastVal = 0.0;
        // FIXME I'm not sure whether there's some other good way to do correct casting,
        // based on the type.
        if (Type.DOUBLE.equals(subjectedAttrType)) {
            firstVal = (Double) firstInEvent.getData()[subjectedAttrIndex];
            lastVal = (Double) lastInEvent.getData()[subjectedAttrIndex];
        } else if (Type.INT.equals(subjectedAttrType)) {
            firstVal = (Integer) firstInEvent.getData()[subjectedAttrIndex];
            lastVal = (Integer) lastInEvent.getData()[subjectedAttrIndex];
        } else if (Type.LONG.equals(subjectedAttrType)) {
            firstVal = (Long) firstInEvent.getData()[subjectedAttrIndex];
            lastVal = (Long) lastInEvent.getData()[subjectedAttrIndex];
        } else if (Type.FLOAT.equals(subjectedAttrType)) {
            firstVal = (Float) firstInEvent.getData()[subjectedAttrIndex];
            lastVal = (Float) lastInEvent.getData()[subjectedAttrIndex];
        }

        long t1 = firstInEvent.getTimeStamp();
        long t2 = lastInEvent.getTimeStamp();
        long millisecondsForASecond = 1000;
        long tGap = t2 - t1 > millisecondsForASecond ? t2 - t1 : millisecondsForASecond;
        double gradient = 0.0;
        if (tGap > 0) {
            gradient = ((lastVal - firstVal) * millisecondsForASecond) / tGap;
        }
        if (log.isDebugEnabled()) {
            log.debug("Gradient: " + gradient + " Last val: " + lastVal +
                    " First val: " + firstVal + " Time Gap: " + tGap + " t1: " + t1 + " t2: " +
                    t2 + " hash: " + this.hashCode());
        }
        Object[] data = firstInEvent.getData().clone();
        data[subjectedAttrIndex] = gradient;
        InEvent gradientEvent =
                new InEvent(firstInEvent.getStreamId(), (t1 + t2) / 2,
                        data);
        InEvent[] output = new InEvent[1];
        output[0] = gradientEvent;
        return output;
    }

    @Override
    protected Object[] currentState() {
        return new Object[]{window.currentState(), oldEventList, newEventList};
    }

    @Override
    protected void restoreState(Object[] data) {
        window.restoreState(data);
        window.restoreState((Object[]) data[0]);
        oldEventList = ((ArrayList<RemoveEvent>) data[1]);
        newEventList = ((ArrayList<InEvent>) data[2]);
        window.reSchedule();
    }

    @Override
    protected void init(Expression[] parameters, QueryPostProcessingElement nextProcessor, AbstractDefinition streamDefinition, String elementId, boolean async, SiddhiContext siddhiContext) {
        if (parameters[0] instanceof IntConstant) {
            timeToKeep = ((IntConstant) parameters[0]).getValue();
        } else {
            timeToKeep = ((LongConstant) parameters[0]).getValue();
        }

        String subjectedAttr = ((Variable) parameters[1]).getAttributeName();
        subjectedAttrIndex = streamDefinition.getAttributePosition(subjectedAttr);
        subjectedAttrType = streamDefinition.getAttributeType(subjectedAttr);

        oldEventList = new ArrayList<RemoveEvent>();
        if (this.siddhiContext.isDistributedProcessingEnabled()) {
            newEventList = this.siddhiContext.getHazelcastInstance().getList(elementId + "-newEventList");
        } else {
            newEventList = new ArrayList<InEvent>();
        }

        if (this.siddhiContext.isDistributedProcessingEnabled()) {
            window = new SchedulerSiddhiQueueGrid<StreamEvent>(elementId, this, this.siddhiContext, this.async);
        } else {
            window = new SchedulerSiddhiQueue<StreamEvent>(this);
        }
        //Ordinary scheduling
        window.schedule();

    }

    @Override
    public void schedule() {
        if (lastSchedule != null) {
            lastSchedule.cancel(false);
        }
        lastSchedule = eventRemoverScheduler.schedule(this, timeToKeep, TimeUnit.MILLISECONDS);
    }

    public void scheduleNow() {
        if (lastSchedule != null) {
            lastSchedule.cancel(false);
        }
        lastSchedule = eventRemoverScheduler.schedule(this, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.eventRemoverScheduler = scheduledExecutorService;
    }

    public void setThreadBarrier(ThreadBarrier threadBarrier) {
        this.threadBarrier = threadBarrier;
    }

    @Override
    public void destroy() {
        oldEventList = null;
        newEventList = null;
        window = null;
    }
}