package com.qrhry.app.domain

/** Validates the small, fixed-condition route graph used by branched games. */
object GameRouteValidator {
    data class Node(val position: Int, val taskCount: Int)

    fun validationError(nodes: List<Node>, route: GameRouteDraft): UserMessageKey? {
        if (route.mode == GameProgressionMode.LINEAR) return null
        if (nodes.isEmpty() || route.startStationPosition == null || route.startStationPosition !in nodes.indices) {
            return UserMessageKey.ROUTE_START_REQUIRED
        }
        if (nodes.map { it.position } != nodes.indices.toList()) return UserMessageKey.ROUTE_STATION_INVALID
        val expected = nodes.associate { node ->
            node.position to if (node.taskCount == 0) setOf(RouteCondition.INFO)
            else setOf(RouteCondition.CORRECT, RouteCondition.INCORRECT)
        }
        if (nodes.any { it.taskCount > 1 }) return UserMessageKey.ROUTE_MULTIPLE_TASKS
        if (route.transitions.any {
                it.sourceStationPosition !in nodes.indices ||
                    (it.targetStationPosition != null && it.targetStationPosition !in nodes.indices)
            }) return UserMessageKey.ROUTE_STATION_INVALID
        val keys = route.transitions.map { it.sourceStationPosition to it.condition }
        if (keys.size != keys.toSet().size) return UserMessageKey.ROUTE_TRANSITION_INVALID
        if (expected.any { (source, conditions) -> conditions.any { condition -> (source to condition) !in keys } }) {
            return UserMessageKey.ROUTE_TRANSITION_MISSING
        }
        if (route.transitions.any { it.condition !in (expected[it.sourceStationPosition] ?: emptySet()) }) {
            return UserMessageKey.ROUTE_TRANSITION_INVALID
        }

        val edges = route.transitions.mapNotNull { transition ->
            transition.targetStationPosition?.let { transition.sourceStationPosition to it }
        }
        val adjacency = nodes.associate { it.position to mutableListOf<Int>() }
        edges.forEach { (from, to) -> adjacency.getValue(from).add(to) }
        val visited = mutableSetOf<Int>()
        val active = mutableSetOf<Int>()
        fun hasCycle(node: Int): Boolean {
            if (node in active) return true
            if (node in visited) return false
            active += node
            if (adjacency.getValue(node).any(::hasCycle)) return true
            active -= node
            visited += node
            return false
        }
        if (nodes.any { hasCycle(it.position) }) return UserMessageKey.ROUTE_CYCLE

        val reachable = mutableSetOf<Int>()
        fun visit(node: Int) {
            if (!reachable.add(node)) return
            adjacency.getValue(node).forEach(::visit)
        }
        visit(checkNotNull(route.startStationPosition))
        if (reachable.size != nodes.size) return UserMessageKey.ROUTE_UNREACHABLE_STATION
        return null
    }
}
