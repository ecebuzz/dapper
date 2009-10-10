/**
 * This file is part of Dapper, the Distributed and Parallel Program Execution Runtime ("this library"). <br />
 * <br />
 * Copyright (C) 2008 Roy Liu, The Regents of the University of California <br />
 * <br />
 * This library is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 2.1 of the License, or (at your option)
 * any later version. <br />
 * <br />
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details. <br />
 * <br />
 * You should have received a copy of the GNU Lesser General Public License along with this library. If not, see <a
 * href="http://www.gnu.org/licenses/">http://www.gnu.org/licenses/</a>.
 */

package dapper.server.flow;

import static dapper.Constants.BLACK;
import static dapper.Constants.DARK_BLUE;
import static dapper.Constants.DARK_GREEN;
import static dapper.Constants.DARK_ORANGE;
import static dapper.Constants.DARK_RED;
import static dapper.Constants.LIGHT_BLUE;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import shared.parallel.Traversable;

/**
 * A logical node class for grouping {@link FlowNode}s into functional equivalence classes.
 * 
 * @apiviz.owns dapper.server.flow.LogicalNodeStatus
 * @author Roy Liu
 */
public class LogicalNode implements Traversable<LogicalNode, LogicalEdge>, Cloneable, Renderable {

    int depth, order;

    LogicalNodeStatus status;

    //

    Set<FlowNode> flowNodes;

    /**
     * The {@link CountDown} on dependencies.
     */
    protected CountDown<LogicalNode> dependencyCountDown;

    /**
     * The {@link CountDown} on clients.
     */
    protected CountDown<FlowNode> clientCountDown;

    List<LogicalEdge> in;
    List<LogicalEdge> out;

    Flow flow;

    /**
     * Default constructor.
     */
    public LogicalNode(Flow flow) {

        this.depth = (this.order = -1);
        this.status = LogicalNodeStatus.PENDING;

        //

        this.flowNodes = new HashSet<FlowNode>();

        this.in = new ArrayList<LogicalEdge>();
        this.out = new ArrayList<LogicalEdge>();

        this.dependencyCountDown = createDependencyCountDown(new HashSet<LogicalNode>());
        this.clientCountDown = createClientCountDown(new HashSet<FlowNode>());

        this.flow = flow;
    }

    /**
     * Creates the dependency {@link CountDown}.
     */
    protected CountDown<LogicalNode> createDependencyCountDown(final Set<LogicalNode> cds) {

        return new AbstractCountDown<LogicalNode>(cds) {

            @Override
            public void reset() {

                cds.clear();

                for (LogicalEdge edge : LogicalNode.this.in) {
                    cds.add(edge.getU());
                }
            }
        };
    }

    /**
     * Creates the client {@link CountDown}.
     */
    protected CountDown<FlowNode> createClientCountDown(final Set<FlowNode> cds) {

        return new AbstractCountDown<FlowNode>(cds) {

            @Override
            public void reset() {

                cds.clear();
                cds.addAll(LogicalNode.this.flowNodes);
            }
        };
    }

    /**
     * Compares two {@link LogicalNode}s on the basis of their assigned DFS order.
     */
    public int compareTo(LogicalNode node) {
        return this.order - node.order;
    }

    /**
     * Creates a {@link LogicalNode} with this node's settings.
     */
    @Override
    public LogicalNode clone() {

        final LogicalNode res;

        try {

            res = (LogicalNode) super.clone();

        } catch (CloneNotSupportedException e) {

            throw new RuntimeException(e);
        }

        res.flowNodes = new HashSet<FlowNode>();

        res.in = new ArrayList<LogicalEdge>();
        res.out = new ArrayList<LogicalEdge>();

        res.dependencyCountDown = null;
        res.clientCountDown = null;

        res.flow = null;

        return res;
    }

    public int getOrder() {
        return this.order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public int getDepth() {
        return this.depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public List<LogicalEdge> getIn() {
        return this.in;
    }

    public List<LogicalEdge> getOut() {
        return this.out;
    }

    /**
     * Gets the {@link CountDown} for dependencies.
     */
    public CountDown<LogicalNode> getDependencyCountDown() {
        return this.dependencyCountDown;
    }

    /**
     * Gets the {@link CountDown} for clients.
     */
    public CountDown<FlowNode> getClientCountDown() {
        return this.clientCountDown;
    }

    /**
     * Gets the {@link LogicalNodeStatus}.
     */
    public LogicalNodeStatus getStatus() {
        return this.status;
    }

    /**
     * Sets the {@link LogicalNodeStatus}.
     */
    public void setStatus(LogicalNodeStatus status) {
        this.status = status;
    }

    /**
     * Gets the {@link Flow}.
     */
    public Flow getFlow() {
        return this.flow;
    }

    /**
     * Sets the {@link Flow}.
     */
    public void setFlow(Flow flow) {
        this.flow = flow;
    }

    /**
     * Gets the {@link FlowNode}s.
     */
    public Set<FlowNode> getFlowNodes() {
        return this.flowNodes;
    }

    /**
     * Delegates to the underlying set of {@link FlowNode}s.
     */
    @Override
    public String toString() {
        return this.flowNodes.toString();
    }

    public void render(Formatter f) {

        SortedSet<FlowNode> flowNodes = new TreeSet<FlowNode>(getFlowNodes());

        boolean isSingleton = (flowNodes.size() == 1);

        final String color;

        switch (getStatus()) {

        case PENDING:
            color = DARK_BLUE;
            break;

        case RESOURCE:
        case PREPARE:
            color = LIGHT_BLUE;
            break;

        case EXECUTE:
            color = DARK_ORANGE;
            break;

        case FINISHED:
            color = DARK_GREEN;
            break;

        case FAILED:
            color = DARK_RED;
            break;

        default:
            color = BLACK;
            break;
        }

        if (!isSingleton) {

            f.format("%nsubgraph cluster_%d {%n", this.order);
            f.format("%n\tcolor = \"#%s\";%n", color);

            for (FlowNode flowNode : flowNodes) {
                flowNode.render(f);
            }

            f.format("}%n");

        } else {

            for (FlowNode flowNode : flowNodes) {
                flowNode.render(f);
            }
        }

        for (FlowNode flowNode : flowNodes) {

            for (FlowEdge flowEdge : flowNode.getOut()) {
                flowEdge.render(f);
            }
        }
    }
}