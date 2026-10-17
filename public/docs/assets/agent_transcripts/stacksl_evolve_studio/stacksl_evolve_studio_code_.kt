// A StackSL search toward a picture, steered from this box's AI panel. Ask, for instance:
// "evolve toward the target, and bring the watchmaker in when it stalls".
// The chat runs generations (evolve_step), looks at the leaders (evolve_look), proposes programs
// (evolve_propose), asks the fast model for a round of edits (evolve_watchmaker), takes a gradient
// step on a leader's numbers (evolve_fit) and changes the metric or the population (evolve_configure).
// The population belongs to the conversation: rewind the chat and the search goes back with it, and
// evolve_crossbreed breeds with any population the conversation has been in, on any branch.
// /population and /sheet show the leaders. For your own picture:
//   StackSLTarget.of("logo", imgFromFile("/path/to/logo.png"))
val evolution = stackSLEvolution(StackSLTargets.rings(32))
stackSLEvolveStudio(evolution)
display("ai-chat", afield.ai.conversation.AiBoxConversation.read(thisBox))
