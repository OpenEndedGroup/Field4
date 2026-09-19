// Read-only mode, end to end.
//
// Open this document normally:      ./field.sh <workspace> -d read_only_session
// Open it read-only:                ./field.sh <workspace> -d read_only_session --read-only
//
// Read-only is also AFIELD_READ_ONLY=1, -Dafield.readOnly=true, or the agent
// backdoor's `open --read-only` for a single session.
//
// What to look for in a read-only session:
//
//   1. An amber pill at the top of the window: READ-ONLY, and in plain words that
//      nothing you change here will be saved.
//   2. Type in any box. The pill becomes a running count of the changes that are
//      going to be discarded. Cursor movement does NOT count -- Monaco's view state
//      is not part of the document.
//   3. Try to close or reload the window. The browser asks you to confirm, because
//      there is work to lose. Field has no explicit save, so this dialog is the only
//      moment a user would otherwise find out.
//   4. Drop a file onto the canvas. Refused, with a reason, instead of silently
//      copying it into a document that will not be written.
//
// Nothing in this document needs a GPU, a network, or any setup.
println("open me with --read-only and read the pill at the top of the window")
