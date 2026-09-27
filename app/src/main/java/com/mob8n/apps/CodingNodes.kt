package com.mob8n.apps

import com.mob8n.core.ExecutionContext
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.bool
import com.mob8n.core.durationMs
import com.mob8n.core.multiline
import com.mob8n.core.out
import com.mob8n.core.text

/**
 * `app.shell_run` (DESIGN4 §7.1): ACTION -> gated by the Agent's askApproval and ALWAYS/coding-toggle in chat; excluded from the chat's
 * node tools because `run_shell` is the same capability. No gates: ProcessBuilder on /system/bin/sh needs no permission.
 */
object ShellRunNode : Node() {
    override val spec = NodeSpec(
        id = "app.shell_run", name = "Run shell command", kind = NodeKind.ACTION,
        description = "Runs a command in /system/bin/sh as Mahout's own sandboxed user (toybox text tools only; no root) with the workspace as cwd; returns stdout, stderr and the exit code.",
        params = listOf(
            multiline("command", "Command", required = true, help = "Runs in /system/bin/sh -c as Mahout's own sandboxed user (toybox only: ls cat grep sed awk find sort head tail wc xargs; no root, no pm/settings/input, no python/node; cannot execute files it wrote — use `sh file.sh`). cwd = the workspace. Put item text into Stdin, not into the command."),
            multiline("stdin", "Stdin", help = "Text piped to the command ({{templates}} allowed) — the safe way to pass item data"),
            text("cwd", "Working directory", templated = false, help = "Workspace-relative; blank = workspace root"),
            durationMs("timeoutMs", "Timeout", 30_000, 1_000, Shell.MAX_TIMEOUT_MS),
            bool("failOnNonZero", "Fail on non-zero exit", true),
        ),
        timeoutMs = 130_000, agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val root = Workspace.root(ctx.requireAndroid())
        val dir = Workspace.resolve(root, ctx.strOrNull("cwd") ?: "")
        val cmd = ctx.req("command")
        ctx.log("sh: " + Shell.redactedCommand(cmd, ctx.persistence.allSecretValues()))
        val r = Shell.run(cmd, dir, ctx.strOrNull("stdin"), ctx.long("timeoutMs") ?: Shell.DEFAULT_TIMEOUT_MS, spillDir = root)
        if (ctx.bool("failOnNonZero") && r.exitCode != 0) throw NodeException("exit ${r.exitCode}: ${r.stderr.take(300)}")
        return out(ctx.item.add(
            "exitCode" to r.exitCode, "stdout" to r.stdout, "stderr" to r.stderr, "truncated" to (r.stdoutTruncated || r.stderrTruncated),
            "timedOut" to r.timedOut, "ms" to r.ms, "outputFile" to r.outputFile,
        ))
    }
}
