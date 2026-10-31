/* One held-out task, drawn by seed: the goal and the checks the agent is judged by. */
val task = AgentEvalCorpus.select(AgentEvalCorpus.load(), "heldout", 1, seed = 3).first()
println(task.id + " (" + task.kind + ")")
println(task.request.goal)
task.request.checks.forEach { println("  check " + it.kind + " " + it.box + " " + it.text) }
