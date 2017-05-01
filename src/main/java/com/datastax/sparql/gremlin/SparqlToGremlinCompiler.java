/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.datastax.sparql.gremlin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.SortCondition;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpConditional;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpGroup;
import org.apache.jena.sparql.algebra.op.OpLeftJoin;
import org.apache.jena.sparql.algebra.op.OpProject;
import org.apache.jena.sparql.algebra.op.OpTopN;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.tinkerpop.gremlin.process.computer.MessageScope.Local;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

// TODO: implement OpVisitor, don't extend OpVisitorBase
public class SparqlToGremlinCompiler extends OpVisitorBase {

	private GraphTraversal<Vertex, ?> traversal;


	List<Traversal> traversalList = new ArrayList<Traversal>();

	String groupVariable = "";
	int sortingDirection = 0;
	long offsetLimit = 0;
	String sortingVariable = "";

	GraphTraversalSource temp;
	Graph graph;

	private SparqlToGremlinCompiler(final GraphTraversal<Vertex, ?> traversal) {
		this.traversal = traversal;
	}

	private SparqlToGremlinCompiler(final GraphTraversalSource g) {
		this(g.V());
		temp = g;

	}

	private SparqlToGremlinCompiler(final Graph g) {
		this.traversal = (GraphTraversal<Vertex, ?>) g.traversal();
		graph = g;
	}

	public String createMatchStep(String step) {
		String st = "";
		step = step.substring(1, step.length() - 2);
		String first = step.substring(0, step.indexOf(","));
		String second = step.substring(step.indexOf(",") + 1);
		System.out.println("First : " + first);
		System.out.println("Second : " + second);
		st = first.substring(first.indexOf("["), first.length() - 1);
		st = "[" + st + "," + second + "]";
		return st;
	}

	GraphTraversal<Vertex, ?> convertToGremlinTraversal(final Query query) {
		final Op op = Algebra.compile(query); // SPARQL query compiles here to
												// OP
		System.out.println("OP Tree: " + op.toString());

		OpWalker.walk(op, this); // OP is being walked here


		int traversalIndex = 0;
		int numberOfTraversal = traversalList.size();
		Traversal arrayOfAllTraversals[] = new Traversal[numberOfTraversal];
		for (Traversal tempTrav : traversalList) {

			arrayOfAllTraversals[traversalIndex++] = tempTrav;

			if (traversalIndex == 1) {

				if (query.hasOrderBy() && !query.hasGroupBy()) {
					List<SortCondition> sortingConditions = query.getOrderBy();
					int directionOfSort = 0;

					for (SortCondition sortCondition : sortingConditions) {
						Expr expr = sortCondition.getExpression();
						directionOfSort = sortCondition.getDirection();
						sortingVariable = expr.getVarName();

					}

					Order orderDirection = Order.incr;
					if (directionOfSort == -1) {
						orderDirection = Order.decr;
					}
					if (!query.hasGroupBy())
						traversal = traversal.order().by(sortingVariable, orderDirection);
					else {
						traversal = traversal.order(Scope.local).by(sortingVariable, orderDirection);
					}
				}
			}
		}

		if (traversalList.size() > 0)
			traversal = traversal.match(arrayOfAllTraversals);


		if (!query.isQueryResultStar() && !query.hasGroupBy()) {

			final List<String> vars = query.getResultVars();
			List<ExprAggregator> lstexpr = query.getAggregators();


			switch (vars.size()) {
			case 0:
				throw new IllegalStateException();
			case 1:
				if (query.isDistinct()) {
					traversal = traversal.dedup(vars.get(0));
				}
				traversal = traversal.select(vars.get(0));
				break;
			case 2:
				if (query.isDistinct()) {
					traversal = traversal.dedup(vars.get(0), vars.get(1));
				}
				traversal = traversal.select(vars.get(0), vars.get(1));
				break;
			default:
				final String[] all = new String[vars.size()];
				vars.toArray(all);
				if (query.isDistinct()) {
					traversal = traversal.dedup(all);
				}
				final String[] others = Arrays.copyOfRange(all, 2, vars.size());
				traversal = traversal.select(vars.get(0), vars.get(1), others);

				break;
			}

		} else {

			if (query.isDistinct()) {
				traversal = traversal.dedup();
			}
		}

		if (query.hasGroupBy()) {
			VarExprList lstExpr = query.getGroupBy();
			String grpVar = "";
			Traversal tempTrav;
			for (Var expr : lstExpr.getVars()) {
				grpVar = expr.getName();
				System.out.println("The Group by var: " + expr.getName());
			}

			traversal = traversal.select(grpVar);
			if (query.hasAggregators()) {
				List<ExprAggregator> exprAgg = query.getAggregators();
				for (ExprAggregator expr : exprAgg) {

					System.out
							.println("The Aggregator by var: " + expr.getAggregator().getName() + " " + expr.getVar());
					if (expr.getAggregator().getName().contains("COUNT")) {
						traversal = traversal.groupCount();
					}
					if (expr.getAggregator().getName().contains("MAX")) {
						traversal = traversal.max();
					}
				}

			} else {

				traversal = traversal.group();
			}
		}

		if (query.hasOrderBy()) {
			List<SortCondition> srtCond = query.getOrderBy();
			int dir = 0;

			for (SortCondition sc : srtCond) {
				Expr expr = sc.getExpression();
				dir = sc.getDirection();
				sortingVariable = expr.getVarName();
			}

			Order odrDir = Order.incr;
			if (dir == -1) {
				odrDir = Order.decr;
			}
			if (query.hasGroupBy()) {

				if (dir == -1)
					traversal = traversal.order(Scope.local).by(Order.valueDecr);
				else
					traversal = traversal.order(Scope.local).by(Order.valueIncr);
			}
		}

		if (query.hasLimit()) {
			long limit = query.getLimit(), offset = 0;

			if (query.hasOffset()) {
				offset = query.getOffset();

			}
			if (query.hasGroupBy() && query.hasOrderBy())
				traversal = traversal.range(Scope.local, offset, offset + limit);
			else
				traversal = traversal.range(offset, offset + limit);

		}
		return traversal;
	}

	private static GraphTraversal<Vertex, ?> convertToGremlinTraversal(final GraphTraversalSource g,
			final Query query) {
		return new SparqlToGremlinCompiler(g).convertToGremlinTraversal(query);
	}

	public static GraphTraversal<Vertex, ?> convertToGremlinTraversal(final Graph graph, final String query) {
		return convertToGremlinTraversal(graph.traversal(),
				QueryFactory.create(Prefixes.prepend(query), Syntax.syntaxSPARQL));
	}

	public static GraphTraversal<Vertex, ?> convertToGremlinTraversal(final GraphTraversalSource g,
			final String query) {
		return convertToGremlinTraversal(g, QueryFactory.create(Prefixes.prepend(query), Syntax.syntaxSPARQL));
	}

	// VISITING SPARQL ALGEBRA OP BASIC TRIPLE PATTERNS - MAYBE
	@Override
	public void visit(final OpBGP opBGP) {
		{
			final List<Triple> triples = opBGP.getPattern().getList();
			final Traversal[] matchTraversals = new Traversal[triples.size()];
			int i = 0;
			for (final Triple triple : triples) {

				matchTraversals[i++] = TraversalBuilder.transform(triple);
				traversalList.add(matchTraversals[i - 1]);
			}

		}

	}

	// VISITING SPARQL ALGEBRA OP FILTER - MAYBE
	@Override
	public void visit(final OpFilter opFilter) {

		Traversal traversal = null;
		for (Expr expr : opFilter.getExprs().getList()) {
			traversal = ((GraphTraversal<Vertex, ?>) traversalList.remove(traversalList.size() - 1))
					.where(WhereTraversalBuilder.transform(expr));
		}
		traversalList.add(traversal);
	}
	// TODO: add more functions for operators other than FILTER, such as
	// OPTIONAL
	// This can be done by understanding how Jena handles these other
	// operators/filters inherently and then map them to Gremlin

	@Override
	public void visit(final OpUnion opUnion) {

		Traversal unionTemp[] = new Traversal[2];

		unionTemp[1] = traversalList.remove(traversalList.size() - 1);
		unionTemp[0] = traversalList.remove(traversalList.size() - 1);

		for (Traversal temp : traversalList) {
			traversal = traversal.match(temp);
		}

		traversal = (GraphTraversal<Vertex, ?>) traversal.union(unionTemp);
		traversalList.add(__.union(unionTemp));
	//	traversalList.clear();
	}
}
