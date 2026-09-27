package com.mob8n.logic

import com.mob8n.core.Node

/** All 27 logic nodes, in DESIGN §4.3 order (+ logic.js, DESIGN4 §7.3). Pure Kotlin except JsNode (Android Context for the JS engine). */
object LogicNodes {
    val all: List<Node> = listOf(
        IfNode, SwitchNode,
        MergeNode, SplitBatchesNode, FlattenNode, AggregateNode, SortNode, LimitNode, UniqueNode,
        DedupeWindowNode, RateLimitNode, DelayNode, WaitUntilNode, WaitApprovalNode,
        SetFieldsNode, TemplateNode, JsonNode, TextNode, MathNode, DateNode,
        CounterNode, RepeatNode, NoteNode, RunWorkflowNode, StopErrorNode, ExecuteOnceNode,
        JsNode,
    )
}
