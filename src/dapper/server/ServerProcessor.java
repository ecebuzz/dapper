/**
 * <p>
 * Copyright (c) 2008-2010 The Regents of the University of California<br>
 * All rights reserved.
 * </p>
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 * </p>
 * <ul>
 * <li>Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.</li>
 * <li>Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.</li>
 * <li>Neither the name of the author nor the names of any contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.</li>
 * </ul>
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * </p>
 */

package dapper.server;

import static dapper.Constants.MAX_INTERNAL_QUEUE_SIZE;
import static dapper.event.ControlEvent.ControlEventType.GET_FLOW_PENDING_COUNT;
import static dapper.event.ControlEvent.ControlEventType.GET_FLOW_PROXY;
import static dapper.event.ControlEvent.ControlEventType.PURGE_FLOW;
import static dapper.event.SourceType.PROCESSOR;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;

import shared.event.EnumStatus;
import shared.event.Handler;
import shared.event.StateProcessor;
import shared.event.StateTable;
import shared.event.Transitions;
import shared.event.Transitions.Transition;
import shared.util.Control;
import shared.util.RequestFuture;
import dapper.client.ClientStatus;
import dapper.codelet.Taggable;
import dapper.event.AddressEvent;
import dapper.event.ControlEvent;
import dapper.event.ControlEvent.ControlEventType;
import dapper.event.ControlEventConnection;
import dapper.event.DataEvent;
import dapper.event.ErrorEvent;
import dapper.event.ExecuteAckEvent;
import dapper.event.FlowEvent;
import dapper.event.FlowEvent.FlowEventType;
import dapper.event.FlowEventBroadcaster;
import dapper.event.ResetEvent;
import dapper.event.SourceType;
import dapper.event.TimeoutEvent;
import dapper.server.flow.Flow;
import dapper.server.flow.FlowBuilder;
import dapper.server.flow.FlowNode;

/**
 * The Dapper server processor.
 * 
 * @apiviz.composedOf dapper.server.ServerLogic
 * @apiviz.owns dapper.server.ServerStatus
 * @apiviz.has dapper.server.ServerProcessor.FlowBuildRequest - - - argument
 * @apiviz.has dapper.server.ServerProcessor.FlowProxy - - - argument
 * @apiviz.has dapper.server.ServerProcessor.RequestEvent - - - event
 * @author Roy Liu
 */
public class ServerProcessor extends StateProcessor<ControlEvent, ControlEventType, SourceType> //
        implements EnumStatus<ServerStatus> {

    final ServerLogic logic;
    final StateTable<ServerStatus, ControlEventType, ControlEvent> fsmInternal;
    final StateTable<ClientStatus, ControlEventType, ControlEvent> fsmClient;
    final FlowEventBroadcaster feb;
    final Set<RequestFuture<?>> futures;

    ServerStatus status;

    /**
     * Default constructor.
     */
    public ServerProcessor(InetAddress address, final Runnable finalizer) {
        super("SEP");

        setFinalizer(new Runnable() {

            @Override
            public void run() {

                ServerProcessor sp = ServerProcessor.this;

                synchronized (sp) {

                    for (RequestFuture<?> future : sp.futures) {
                        future.setException(new IllegalStateException("The processing thread has exited"));
                    }

                    sp.setStatus(ServerStatus.INVALID);
                    sp.notifyAll();
                }

                Control.close(sp.feb);

                finalizer.run();
            }
        });

        this.logic = new ServerLogic(address, this);
        this.fsmInternal = new StateTable<ServerStatus, ControlEventType, ControlEvent>(this, //
                ServerStatus.class, ControlEventType.class, //
                "internal");
        this.fsmClient = new StateTable<ClientStatus, ControlEventType, ControlEvent>(this, //
                ClientStatus.class, ControlEventType.class, //
                "client");

        this.feb = new FlowEventBroadcaster(MAX_INTERNAL_QUEUE_SIZE, this);
        this.futures = Collections.newSetFromMap(new WeakHashMap<RequestFuture<?>, Boolean>());

        this.status = ServerStatus.RUN;
    }

    @Override
    public SourceType getType() {
        return PROCESSOR;
    }

    @Override
    public void handle(ControlEvent evt) {

        checkCurrentThread();

        switch (evt.getSource().getType()) {

        case PROCESSOR:
            this.fsmInternal.lookup(this, evt);
            break;

        case CONNECTION:
            this.fsmClient.lookup((ClientState) evt.getSource().getHandler(), evt);
            break;

        default:
            throw new AssertionError("Control should never reach here");
        }
    }

    @Override
    public ServerStatus getStatus() {
        return this.status;
    }

    @Override
    public void setStatus(ServerStatus status) {
        this.status = status;
    }

    // INTERNAL LOGIC

    @Transition(currentState = "RUN", eventType = "REFRESH", group = "internal")
    final Handler<ControlEvent> refreshHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleRefresh();
        }
    };

    @Transition(currentState = "RUN", eventType = "CREATE_FLOW", group = "internal")
    final Handler<ControlEvent> createFlowHandler = new Handler<ControlEvent>() {

        @SuppressWarnings("unchecked")
        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleCreateFlow((RequestEvent<FlowBuildRequest, FlowProxy>) evt);
        }
    };

    @Transition(currentState = "RUN", eventType = "PURGE_FLOW", group = "internal")
    final Handler<ControlEvent> purgeFlowHandler = new Handler<ControlEvent>() {

        @SuppressWarnings("unchecked")
        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handlePurgeFlow((RequestEvent<Flow, Object>) evt);
        }
    };

    @Transition(currentState = "RUN", eventType = "SET_AUTOCLOSE_IDLE", group = "internal")
    final Handler<ControlEvent> setAutocloseIdleHandler = new Handler<ControlEvent>() {

        @SuppressWarnings("unchecked")
        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleSetAutocloseIdle((RequestEvent<Boolean, Object>) evt);
        }
    };

    @Transition(currentState = "RUN", eventType = "GET_FLOW_PROXY", group = "internal")
    final Handler<ControlEvent> getFlowProxyHandler = new Handler<ControlEvent>() {

        @SuppressWarnings("unchecked")
        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleGetFlowProxy((RequestEvent<Flow, List<FlowProxy>>) evt);
        }
    };

    @Transition(currentState = "RUN", eventType = "GET_PENDING_COUNT", group = "internal")
    final Handler<ControlEvent> getPendingCountHandler = new Handler<ControlEvent>() {

        @SuppressWarnings("unchecked")
        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleGetPendingCount((RequestEvent<Object, Integer>) evt);
        }
    };

    @Transition(currentState = "RUN", eventType = "GET_FLOW_PENDING_COUNT", group = "internal")
    final Handler<ControlEvent> getFlowPendingCountHandler = new Handler<ControlEvent>() {

        @SuppressWarnings("unchecked")
        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleGetFlowPendingCount((RequestEvent<Flow, Integer>) evt);
        }
    };

    @Transition(currentState = "RUN", eventType = "CREATE_USER_QUEUE", group = "internal")
    final Handler<ControlEvent> createUserQueueHandler = new Handler<ControlEvent>() {

        @SuppressWarnings("unchecked")
        @Override
        public void handle(ControlEvent evt) {
            ((RequestEvent<Flow, BlockingQueue<FlowEvent<Object, Object>>>) evt) //
                    .setOutput(ServerProcessor.this.feb.createUserQueue());
        }
    };

    @Transitions(transitions = {
            //
            @Transition(currentState = "RUN", eventType = "SUSPEND", group = "internal"), //
            @Transition(currentState = "RUN", eventType = "RESUME", group = "internal") //
    })
    final Handler<ControlEvent> suspendResumeHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleSuspendResume(evt);
        }
    };

    // CLIENT LOGIC

    @Transitions(transitions = {
            //
            @Transition(currentState = "WAIT", eventType = "END_OF_STREAM", group = "client"), //
            @Transition(currentState = "RESOURCE", eventType = "END_OF_STREAM", group = "client"), //
            @Transition(currentState = "RESOURCE_ACK", eventType = "END_OF_STREAM", group = "client"), //
            @Transition(currentState = "PREPARE", eventType = "END_OF_STREAM", group = "client"), //
            @Transition(currentState = "PREPARE_ACK", eventType = "END_OF_STREAM", group = "client"), //
            @Transition(currentState = "EXECUTE", eventType = "END_OF_STREAM", group = "client") //
    })
    final Handler<ControlEvent> eosHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleEOS((ControlEventConnection) evt.getSource());
        }
    };

    @Transitions(transitions = {
            //
            @Transition(currentState = "WAIT", eventType = "ERROR", group = "client"), //
            @Transition(currentState = "RESOURCE", eventType = "ERROR", group = "client"), //
            @Transition(currentState = "RESOURCE_ACK", eventType = "ERROR", group = "client"), //
            @Transition(currentState = "PREPARE", eventType = "ERROR", group = "client"), //
            @Transition(currentState = "PREPARE_ACK", eventType = "ERROR", group = "client"), //
            @Transition(currentState = "EXECUTE", eventType = "ERROR", group = "client") //
    })
    final Handler<ControlEvent> errorHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleError((ErrorEvent) evt);
        }
    };

    @Transitions(transitions = {
            //
            @Transition(currentState = "RESOURCE", eventType = "TIMEOUT", group = "client"), //
            @Transition(currentState = "PREPARE", eventType = "TIMEOUT", group = "client"), //
            @Transition(currentState = "EXECUTE", eventType = "TIMEOUT", group = "client") //
    })
    final Handler<ControlEvent> timeoutHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleTimeout((TimeoutEvent) evt);
        }
    };

    @Transition(currentState = "EXECUTE", eventType = "DATA", group = "client")
    final Handler<ControlEvent> dataHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleData((DataEvent) evt);
        }
    };

    @Transitions(transitions = {
            //
            // Reset a node because some one of its clients failed to acquire resources.
            @Transition(currentState = "PREPARE", eventType = "RESET", group = "client"), //
            // Reset a node because some one of its clients failed to acquire resources.
            @Transition(currentState = "PREPARE_ACK", eventType = "RESET", group = "client"), //
            // Reset a node because some one of its clients failed in execution.
            @Transition(currentState = "EXECUTE", eventType = "RESET", group = "client") //
    })
    final Handler<ControlEvent> resetHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleReset((ResetEvent) evt);
        }
    };

    @Transition(currentState = "IDLE", eventType = "ADDRESS", group = "client")
    final Handler<ControlEvent> idleToWaitHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleIdleToWait((AddressEvent) evt);
        }
    };

    @Transition(currentState = "RESOURCE", eventType = "RESOURCE_ACK", group = "client")
    final Handler<ControlEvent> resourceToPrepareHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleResourceToPrepare((ClientState) evt.getSource().getHandler());
        }
    };

    @Transition(currentState = "PREPARE", eventType = "PREPARE_ACK", group = "client")
    final Handler<ControlEvent> prepareToExecuteHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handlePrepareToExecute((ClientState) evt.getSource().getHandler());
        }
    };

    @Transition(currentState = "EXECUTE", eventType = "EXECUTE_ACK", group = "client")
    final Handler<ControlEvent> executeToWaitHandler = new Handler<ControlEvent>() {

        @Override
        public void handle(ControlEvent evt) {
            ServerProcessor.this.logic.handleExecuteToWait((ExecuteAckEvent) evt);
        }
    };

    /**
     * Creates and fires a new {@link RequestEvent}.
     * 
     * @param <S>
     *            the input type.
     * @param <T>
     *            the output type.
     * @throws InterruptedException
     *             when this operation is interrupted.
     * @throws ExecutionException
     *             when something goes awry.
     */
    protected <S, T> T request(ControlEventType type, S input) throws InterruptedException, ExecutionException {

        RequestEvent<S, T> evt = new RequestEvent<S, T>(type, input);
        onLocal(evt);

        return evt.future.get();
    }

    /**
     * Creates a {@link RequestFuture}.
     * 
     * @param <T>
     *            the result type.
     */
    protected <T> RequestFuture<T> createFuture() {

        RequestFuture<T> rf = new RequestFuture<T>();

        synchronized (this) {

            Control.checkTrue(getStatus() != ServerStatus.INVALID, //
                    "The processing thread has exited");

            this.futures.add(rf);
        }

        return rf;
    }

    /**
     * Broadcasts a {@link FlowEvent} constructed from the given information.
     * 
     * @param <F>
     *            the {@link Flow} attachment type.
     * @param <N>
     *            the {@link FlowNode} attachment type.
     */
    public <F, N> void broadcast(FlowEventType type, F flowAttachment, N flowNodeAttachment, Throwable exception) {
        this.feb.add(new FlowEvent<F, N>(type, flowAttachment, flowNodeAttachment, exception));
    }

    /**
     * A subclass of {@link ControlEvent} for retrieving the internal state of the {@link ServerLogic} in a thread-safe
     * way.
     * 
     * @param <S>
     *            the input type.
     * @param <T>
     *            the output type.
     */
    protected class RequestEvent<S, T> extends ControlEvent {

        final S input;
        final RequestFuture<T> future;

        /**
         * Default constructor.
         */
        protected RequestEvent(ControlEventType type, S input) {
            super(type, ServerProcessor.this);

            this.input = input;
            this.future = createFuture();
        }

        /**
         * Gets the input.
         */
        protected S getInput() {
            return this.input;
        }

        /**
         * Sets the {@link RequestFuture} result.
         */
        protected void setOutput(T value) {
            this.future.set(value);
        }

        /**
         * Sets a {@link RequestFuture} exception.
         */
        protected void setException(Throwable t) {
            this.future.setException(t);
        }
    }

    /**
     * A wrapper for {@link Flow}s gotten from the server.
     */
    public class FlowProxy implements Taggable<Object> {

        Flow flow;

        final Flow originalFlow;
        final int flowFlags;
        final RequestFuture<Object> future;

        /**
         * Default constructor.
         */
        protected FlowProxy(Flow originalFlow, int flowFlags) {

            this.originalFlow = originalFlow;
            this.flowFlags = flowFlags;

            this.flow = originalFlow.clone();
            this.future = createFuture();
        }

        @Override
        public Object getAttachment() {
            return this.originalFlow.getAttachment();
        }

        @Override
        public FlowProxy setAttachment(Object attachment) {

            this.originalFlow.setAttachment(attachment);

            return this;
        }

        /**
         * Gets the current {@link Flow}.
         */
        public Flow getFlow() {
            return this.flow;
        }

        /**
         * Sets the current {@link Flow}.
         */
        public void setFlow(Flow flow) {
            this.flow = flow;
        }

        /**
         * Delegates to the current {@link Flow}'s {@link Flow#toString()} method.
         */
        @Override
        public String toString() {

            Formatter f = new Formatter();
            this.flow.render(f);

            return f.toString();
        }

        /**
         * Purges the {@link Flow}.
         * 
         * @throws InterruptedException
         *             when this operation is interrupted.
         * @throws ExecutionException
         *             when something goes awry.
         */
        public void purge() throws InterruptedException, ExecutionException {
            request(PURGE_FLOW, this.originalFlow);
        }

        /**
         * Refreshes the {@link Flow}.
         * 
         * @throws InterruptedException
         *             when this operation is interrupted.
         * @throws ExecutionException
         *             when something goes awry.
         */
        public void refresh() throws InterruptedException, ExecutionException {
            request(GET_FLOW_PROXY, this.originalFlow);
        }

        /**
         * Gets the number of additional clients required to saturate all pending computations on the {@link Flow}.
         * 
         * @throws InterruptedException
         *             when this operation is interrupted.
         * @throws ExecutionException
         *             when something goes awry.
         */
        public int getPendingCount() throws InterruptedException, ExecutionException {
            return (Integer) request(GET_FLOW_PENDING_COUNT, (Object) this.originalFlow);
        }

        /**
         * Waits for the {@link Flow} to finish.
         * 
         * @throws InterruptedException
         *             when this operation is interrupted.
         * @throws ExecutionException
         *             when something goes awry.
         */
        public void await() throws InterruptedException, ExecutionException {
            this.future.get();
        }

        /**
         * Sets the {@link RequestFuture} result.
         */
        protected void setOutput(Object value) {
            this.future.set(value);
        }

        /**
         * Sets a {@link RequestFuture} exception.
         */
        protected void setException(Throwable t) {
            this.future.setException(t);
        }

        /**
         * On {@link Flow} begin.
         */
        protected void onFlowBegin(Object flowAttachment) {

            if ((this.flowFlags & FlowEvent.F_FLOW) != 0) {
                broadcast(FlowEventType.FLOW_BEGIN, flowAttachment, (Object) null, null);
            }
        }

        /**
         * On {@link Flow} end.
         */
        protected void onFlowEnd(Object flowAttachment) {

            if ((this.flowFlags & FlowEvent.F_FLOW) != 0) {
                broadcast(FlowEventType.FLOW_END, flowAttachment, (Object) null, null);
            }
        }

        /**
         * On {@link Flow} error.
         */
        protected void onFlowError(Object flowAttachment, Throwable exception) {

            if ((this.flowFlags & FlowEvent.F_FLOW) != 0) {
                broadcast(FlowEventType.FLOW_ERROR, flowAttachment, (Object) null, exception);
            }
        }

        /**
         * On {@link FlowNode} begin.
         */
        protected void onFlowNodeBegin(Object flowAttachment, Object flowNodeAttachment) {

            if ((this.flowFlags & FlowEvent.F_FLOW_NODE) != 0) {
                broadcast(FlowEventType.FLOW_NODE_BEGIN, flowAttachment, flowNodeAttachment, null);
            }
        }

        /**
         * On {@link FlowNode} end.
         */
        protected void onFlowNodeEnd(Object flowAttachment, Object flowNodeAttachment) {

            if ((this.flowFlags & FlowEvent.F_FLOW_NODE) != 0) {
                broadcast(FlowEventType.FLOW_NODE_END, flowAttachment, flowNodeAttachment, null);
            }
        }

        /**
         * On {@link FlowNode} error.
         */
        protected void onFlowNodeError(Object flowAttachment, Object flowNodeAttachment, Throwable exception) {

            if ((this.flowFlags & FlowEvent.F_FLOW_NODE) != 0) {
                broadcast(FlowEventType.FLOW_NODE_ERROR, flowAttachment, flowNodeAttachment, exception);
            }
        }
    }

    /**
     * A container class for requesting that a {@link Flow} be built.
     */
    public static class FlowBuildRequest {

        /**
         * The {@link FlowBuilder} to use.
         */
        final public FlowBuilder flowBuilder;

        /**
         * The {@link ClassLoader} to use.
         */
        final public ClassLoader classLoader;

        /**
         * The bit vector of {@link FlowEvent} interest flags.
         */
        final public int flowFlags;

        /**
         * Default constructor.
         */
        public FlowBuildRequest(FlowBuilder flowBuilder, ClassLoader classLoader, int flowFlags) {

            this.flowBuilder = flowBuilder;
            this.classLoader = classLoader;
            this.flowFlags = flowFlags;
        }
    }
}
