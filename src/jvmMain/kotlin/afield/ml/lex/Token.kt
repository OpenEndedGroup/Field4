package afield.ml.lex

import afield.ml.diag.SourceRange

/**
 * One [TokenKind] for every lexeme FieldML has, and the [Token] that pairs a kind with the text it
 * was read from.
 *
 * There is no kind for whitespace or for a comment: the lexer skips trivia instead of emitting it,
 * so a token list cannot be printed back as the source it came from. Keywords are kinds of their
 * own rather than identifiers with reserved text, which is what lets the parser ask for `LET` or
 * `WITH` by kind, and also what stops those words being used as names.
 */

/**
 * Every lexeme FieldML has: its keywords, its identifier, its three literal kinds, its punctuation,
 * its operators, and `EOF`.
 *
 * `TRUE` and `FALSE` are keywords here rather than one boolean literal kind, so the parser reads
 * their value from the kind and never from the text. `EOF` is not read out of the source at all;
 * the lexer appends it when it reaches the end.
 */
enum class TokenKind {
    LET,
    TYPE,
    IN,
    IF,
    THEN,
    ELSE,
    FUN,
    MATCH,
    WITH,
    TRUE,
    FALSE,
    IDENT,
    INT,
    FLOAT,
    STRING,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    LBRACE,
    RBRACE,
    COMMA,
    SEMICOLON,
    BAR,
    PIPE_FORWARD,
    DOT,
    ARROW,
    EQ,
    EQEQ,
    NEQ,
    PLUS,
    PLUSPLUS,
    MINUS,
    STAR,
    SLASH,
    AMPAMP,
    BARBAR,
    COLONCOLON,
    LT,
    LTE,
    GT,
    GTE,
    EOF,
}

/** A lexeme: what it is, the text it was read from, and where that text was. */
data class Token(
    /** Which lexeme this is. */
    val kind: TokenKind,
    /**
     * The exact slice of source the token was read from, undecoded: a `STRING` still has its quotes
     * and its backslashes, and an `INT` is still text. Converting them is the parser's job. Empty
     * for `EOF`.
     */
    val text: String,
    /**
     * Where [text] was. `EOF` gets an empty range at the end of the source, so a diagnostic about a
     * program that stopped too early still has somewhere to point.
     */
    val range: SourceRange,
)
