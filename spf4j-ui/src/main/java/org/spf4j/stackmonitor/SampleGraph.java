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
package org.spf4j.stackmonitor;

import com.google.common.annotations.Beta;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import gnu.trove.map.TMap;
import gnu.trove.map.hash.THashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.spf4j.base.Method;

/**
 *
 * @author Zoltan Farkas
 */
@Beta
public final class SampleGraph {

  public static final class SampleVertexKey {
    private final Method method;
    private int idxInHierarchy;
    public SampleVertexKey(final Method method, final int idxInHierarchy) {
      this.method = method;
      this.idxInHierarchy = idxInHierarchy;
    }

    @Override
    public int hashCode() {
      int hash = 59 + Objects.hashCode(this.method);
      return 59 * hash + this.idxInHierarchy;
    }

    @Override
    public boolean equals(final Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final SampleVertexKey other = (SampleVertexKey) obj;
      if (this.idxInHierarchy != other.idxInHierarchy) {
        return false;
      }
      return Objects.equals(this.method, other.method);
    }

  }

  @SuppressWarnings("checkstyle:VisibilityModifier")
  public static class SampleVertex {

    private final SampleVertexKey key;
    protected int nrSamples;

    public SampleVertex(final SampleVertexKey key, final int nrSamples) {
      this.key = key;
      this.nrSamples = nrSamples;
    }

    public final SampleVertexKey getKey() {
      return key;
    }

    public final int getNrSamples() {
      return nrSamples;
    }

  }

  public static final class AggSampleVertex extends SampleVertex {

    public AggSampleVertex(final SampleVertexKey key, final int nrSamples) {
      super(key, nrSamples);
    }

    public int addSamples(final int pnrSamples) {
     this.nrSamples += pnrSamples;
     return this.nrSamples;
    }

  }

  private static final class Traversal {

    private final SampleVertex parent;
    private final Method method;
    private final SampleNode node;

    Traversal(final SampleVertex parent, final Method method, final SampleNode node) {
      this.parent = parent;
      this.method = method;
      this.node = node;
    }

  }

  private final SetMultimap<SampleVertexKey, SampleVertex> vertexMap;
  private final Map<SampleVertexKey, AggSampleVertex> aggregates;

  /**
   * A graph representation of the stack trace tree.
   */
  private final MutableGraph<SampleVertex> sg;

  /**
   * An aggreagated representation of the stack trace tree;
   */
  private final MutableGraph<AggSampleVertex> aggGraph;

  private final SampleVertex rootVertex;


  public SampleGraph(final Method m, final SampleNode node) {
    int nrNodes = node.getNrNodes();
    vertexMap = MultimapBuilder.hashKeys(nrNodes).hashSetValues(1).build();
    aggregates = new THashMap<>(nrNodes);
    sg = GraphBuilder.directed()
            .nodeOrder(ElementOrder.unordered())
            .expectedNodeCount(nrNodes)
            .build();
    aggGraph = GraphBuilder.directed()
            .nodeOrder(ElementOrder.unordered())
            .expectedNodeCount(nrNodes)
            .build();
    rootVertex = tree2Graph(m, node);
  }




  /**
   * Compute a duplication occurrence from root index for a method.
   * (number of occurrences of this method on a stack path.
   * @param from
   * @param m
   * @return
   */
  private int computeMethodIdx(final SampleVertex from, final Method m) {
    if (from.key.method.equals(m)) {
      return from.key.idxInHierarchy + 1;
    } else {
      Set<SampleVertex> predecessors = sg.predecessors(from);
      int size = predecessors.size();
      if (size == 1) {
        return computeMethodIdx(predecessors.iterator().next(), m);
      } else if (size > 1) {
        throw new IllegalStateException("Cannot have multiple predecesors for " + from + ", pred = " + predecessors);
      } else {
        return 0;
      }
    }
  }


  private SampleVertex tree2Graph(final Method m, final SampleNode node) {
    SampleVertex parentVertex = new SampleVertex(new SampleVertexKey(m, 0), node.getSampleCount());
    if (!sg.addNode(parentVertex)) {
      throw new IllegalStateException();
    }
    if (!vertexMap.put(parentVertex.key, parentVertex)) {
      throw new IllegalStateException();
    }
    AggSampleVertex aggSampleVertex = new AggSampleVertex(parentVertex.key, parentVertex.nrSamples);
    aggGraph.addNode(aggSampleVertex);
    aggregates.put(parentVertex.key, aggSampleVertex);
    Deque<Traversal> dq = new ArrayDeque<>();
    TMap<Method, SampleNode> subNodes = node.getSubNodes();
    if (subNodes != null) {
      subNodes.forEachEntry((k, v) -> {
        dq.add(new Traversal(parentVertex, k, v));
        return true;
      });
    }
    Traversal t;
    while ((t = dq.pollLast()) != null) {
      SampleVertexKey vtxk = new SampleVertexKey(t.method, computeMethodIdx(t.parent, t.method));
      SampleVertex vtx = new SampleVertex(vtxk, t.node.getSampleCount());
      if (!sg.addNode(vtx)) {
        throw new IllegalStateException();
      }
      if (!vertexMap.put(vtx.key, vtx)) {
        throw new IllegalStateException();
      }
      AggSampleVertex aggParent = aggregates.get(t.parent.key);
      AggSampleVertex current = aggregates.get(vtxk);
      if (current == null) {
        current = new AggSampleVertex(vtxk, vtx.nrSamples);
        aggGraph.addNode(current);
        aggregates.put(vtxk, current);
      } else {
        current.addSamples(vtx.nrSamples);
      }
      aggGraph.putEdge(aggParent, current);
      if (!sg.putEdge(t.parent, vtx)) {
        throw new IllegalStateException();
      }
      TMap<Method, SampleNode> subNodes2 = t.node.getSubNodes();
      if (subNodes2 != null) {
        subNodes2.forEachEntry((k, v) -> {
          dq.add(new Traversal(vtx, k, v));
          return true;
        });
      }
    }
    return parentVertex;
  }


  public SampleVertex getRootVertex() {
    return rootVertex;
  }

  @Override
  public String toString() {
    return "SampleGraph{" + "vertexMap=" + vertexMap + ", sg=" + sg + ", rootVertex=" + rootVertex + '}';
  }



}