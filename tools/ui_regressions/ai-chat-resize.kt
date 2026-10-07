display("ai-chat", "<<you>>\nPlease inspect the house.\n<<ai>>\n" + (1..80).joinToString("\n\n") { "Inspection line $it: the current design and its checked panel evidence remain available." })
