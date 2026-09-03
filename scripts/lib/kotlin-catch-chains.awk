# Emits one record per `catch` clause in ONE Kotlin file, for the #674 gate.
#
# Read scripts/check-cancellation-catch-order.sh first: it owns the rule, the
# allowlist and the backlog. This file owns exactly one question -- "for each
# catch clause, what does it catch, and does an earlier clause of the SAME
# try/catch chain catch a CancellationException?" -- and answers it from source
# order, which is the only thing that decides delivery (the Kotlin compiler
# diagnoses neither an unreachable nor a mis-ordered catch clause).
#
# Output, tab-separated, on stdout:
#
#   CATCH <line> <type> <guarded:0|1> <enclosing-function>
#   TOTAL <count>
#
# `<guarded>` is 1 iff some EARLIER clause of the same chain catches a type
# whose last dotted component is `CancellationException`. Both spellings in
# this tree -- bare, and qualified `kotlinx.coroutines.` / `java.util.concurrent.`
# -- therefore count, which a regex over one spelling would not (#667).
#
# `TOTAL` exists so the caller can refuse to report "clean" for a tree it never
# read. A scanner that reaches nothing and one that finds nothing print the
# same empty violation list.
#
# WHY A SCANNER AND NOT A REGEX
# -----------------------------
# The property is ORDER WITHIN ONE CHAIN, and neither half survives a line
# regex. `} catch (e: Exception) {` on the line after a
# `} catch (e: CancellationException) {` may belong to a different try, three
# lines of a nested block apart; and this tree already contains a chain whose
# clause spans four lines:
#
#     } catch (
#         @Suppress("TooGenericExceptionCaught")
#         t: Throwable
#     ) {
#
# So: strip comments and string literals (blanked, not deleted, so line numbers
# survive), then walk tokens tracking brace depth. A frame opened right after
# `try` or right after a `catch (...)` is part of a chain; when it closes, the
# next token decides whether the chain continues. Any other token ends it, which
# is what keeps a catch belonging to one try from being credited to another.

function is_ident_char(c) { return (c ~ /[A-Za-z0-9_$]/) }

BEGIN { inblock = 0; blockdepth = 0; inraw = 0; instr = ""; tmpl = 0; nlines = 0 }

# Pass 1: blank out comments, string literals and character literals, in place.
# Blanking rather than deleting keeps every character at its original line, so
# the reported line number is the line the `catch` keyword is actually on.
#
# The state (`inblock`, `inraw`, `instr`, `tmpl`) carries ACROSS lines, because
# three Kotlin constructs span them and one of them is not obvious:
#
#   * block comments, which also nest, so the depth is counted;
#   * `"""` raw strings;
#   * an ordinary `"` string whose `${ ... }` template contains a newline. That
#     is legal and this tree has one -- `TestRelayFixture.kt:392` interpolates a
#     `runCatching { ... }` block across three lines. Blanking only to end of
#     line there left `}.getOrDefault(-1)}) " +` to be read as CODE, and the two
#     stray closing braces shifted the depth counter for the rest of the file:
#     every chain decision after it was made at the wrong depth.
{
  line = $0; out = ""; i = 1; n = length(line)
  while (i <= n) {
    c = substr(line, i, 1); c2 = substr(line, i, 2); c3 = substr(line, i, 3)
    if (inblock) {
      # Kotlin block comments nest, so this counts rather than scanning for the
      # first `*/`.
      if (c2 == "*/") { blockdepth--; if (blockdepth == 0) inblock = 0; out = out "  "; i += 2; continue }
      if (c2 == "/*") { blockdepth++; out = out "  "; i += 2; continue }
      out = out " "; i++; continue
    }
    if (inraw) {
      if (c3 == "\"\"\"") { inraw = 0; out = out "   "; i += 3; continue }
      out = out " "; i++; continue
    }
    if (instr != "") {
      if (tmpl > 0) {
        # Inside `${ ... }`. Everything is blanked, INCLUDING the braces: they
        # are balanced within the literal, so letting them reach the depth
        # counter would be the bug this branch exists to avoid. They are counted
        # here only to find where the template ends.
        if (c == "{") tmpl++
        else if (c == "}") tmpl--
        out = out " "; i++; continue
      }
      if (c == "\\") { out = out "  "; i += 2; continue }
      if (c2 == "${") { tmpl = 1; out = out "  "; i += 2; continue }
      if (c == instr) { instr = ""; out = out " "; i++; continue }
      out = out " "; i++; continue
    }
    if (c2 == "//") { while (i <= n) { out = out " "; i++ }; break }
    if (c2 == "/*") { inblock = 1; blockdepth = 1; out = out "  "; i += 2; continue }
    if (c3 == "\"\"\"") { inraw = 1; out = out "   "; i += 3; continue }
    if (c == "\"" || c == "'") { instr = c; tmpl = 0; out = out " "; i++; continue }
    out = out c; i++
  }
  # An ordinary string literal cannot contain a bare newline, so one still open
  # here with no template pending is a lexing disagreement, not a continuation.
  # Reset rather than swallow the rest of the file.
  if (instr != "" && tmpl == 0) instr = ""
  nlines++; L[nlines] = out
}

END {
  # One buffer plus a character->line map, so a clause spanning lines is one
  # token run rather than a per-line special case.
  buf = ""; pos = 0
  for (li = 1; li <= nlines; li++) {
    s = L[li]; plen = length(s)
    for (j = 1; j <= plen; j++) { pos++; lineat[pos] = li }
    buf = buf s
    pos++; lineat[pos] = li; buf = buf "\n"
  }
  parse()
  print "TOTAL\t" total
}

function parse(   i, n, c, tok, start, depth, pdepth,
                  pend_try, pend_type, pend_cancel,
                  chain_depth, chain_cancel, ty, guarded, who,
                  pend_fun, pend_name, pend_params, lastfun) {
  n = length(buf); i = 1; depth = 0; pdepth = 0
  pend_try = 0; pend_type = ""; pend_cancel = 0
  # `chain_depth` is the depth at which a try/catch frame JUST closed, i.e. the
  # only depth at which the very next token may continue a chain. Any other
  # token resets it to -1, so a `catch` that follows unrelated code -- or that
  # belongs to a different try -- starts a fresh chain with guarded = 0.
  chain_depth = -1; chain_cancel = 0
  pend_fun = 0; pend_name = ""; pend_params = 0; lastfun = ""
  fname[0] = ""
  while (i <= n) {
    c = substr(buf, i, 1)
    if (c == " " || c == "\t" || c == "\r" || c == "\n") { i++; continue }
    if (is_ident_char(c)) {
      start = i
      while (i <= n && is_ident_char(substr(buf, i, 1))) i++
      tok = substr(buf, start, i - start)
      if (tok == "fun") { pend_fun = 1; pend_name = ""; pend_params = 0; chain_depth = -1; continue }
      # `fun interface Foo { ... }` is a SAM declaration, not a function: its
      # brace opens an interface body. Left armed, the next brace at top level
      # is misread as that "function"'s body and every catch under it is
      # attributed to the interface's name.
      if (tok == "interface" && pend_fun && pend_params == 0) { pend_fun = 0; pend_name = ""; chain_depth = -1; continue }
      # Only up to the parameter list: `fun invoke(...): ToolResult {` must
      # name `invoke`, not its return type. A receiver or a type parameter
      # before the name is overwritten by the name itself.
      if (pend_fun && pdepth == 0 && pend_params == 0) { pend_name = tok }
      if (tok == "try") { pend_try = 1; chain_depth = -1; continue }
      if (tok == "catch") {
        while (i <= n && substr(buf, i, 1) ~ /[ \t\r\n]/) i++
        # `catch` not followed by `(` is not a catch clause -- an identifier of
        # that name, or `Flow.catch { }`, which is a different construct with
        # different semantics (#667 cleared those separately).
        if (substr(buf, i, 1) != "(") { chain_depth = -1; continue }
        ty = read_param(i); i = G_end
        guarded = (chain_depth == depth) ? chain_cancel : 0
        total++
        who = fname[depth]
        if (who == "") who = lastfun
        # A catch in a property initialiser belongs to no function. Named
        # rather than left blank: the ledger key is <path, function> and an
        # empty field would collide with a parse failure.
        if (who == "") who = "<top-level>"
        print "CATCH\t" lineat[start] "\t" ty "\t" guarded "\t" who
        pend_type = ty
        pend_cancel = guarded
        if (is_cancellation(ty)) pend_cancel = 1
        chain_depth = -1
        continue
      }
      chain_depth = -1
      continue
    }
    if (c == "(") { if (pend_fun && pdepth == 0) pend_params = 1; pdepth++; chain_depth = -1; i++; continue }
    if (c == ")") { pdepth--; chain_depth = -1; i++; continue }
    if (c == "{") {
      depth++
      if (pend_try) { fkind[depth] = "try"; fcancel[depth] = 0; pend_try = 0 }
      else if (pend_type != "") { fkind[depth] = "catch"; fcancel[depth] = pend_cancel; pend_type = "" }
      else if (pend_fun && pdepth == 0) { fkind[depth] = "fun"; pend_fun = 0; lastfun = pend_name }
      else { fkind[depth] = "other" }
      fname[depth] = (fkind[depth] == "fun") ? pend_name : fname[depth - 1]
      chain_depth = -1; i++; continue
    }
    if (c == "}") {
      # A brace-less declaration -- `suspend fun obtain(x: ByteArray): Foo?` in
      # an interface or an abstract member -- leaves the header armed with no
      # body to consume it. The enclosing `}` is where it is known to be over;
      # without this the NEXT brace at that level is claimed as its body.
      pend_fun = 0
      if (fkind[depth] == "try") { chain_depth = depth - 1; chain_cancel = 0 }
      else if (fkind[depth] == "catch") { chain_depth = depth - 1; chain_cancel = fcancel[depth] }
      else { chain_depth = -1 }
      fkind[depth] = ""; fname[depth] = ""
      depth--
      i++; continue
    }
    # `=` ends a function header, so an expression-bodied `fun f() = ...` does
    # not leave `pend_fun` armed for the next brace it meets. Its name is
    # recorded anyway: an expression body opens no frame, and this tree puts
    # whole try/catch chains in one (`KeypairDeviceCredentialProvider.kt:46`).
    # Only at pdepth 0. A default parameter value -- `baseDomain: String =
    # defaultBaseDomain` -- also contains `=`, and disarming there loses the
    # header of every function that has one.
    if (c == "=" && pdepth == 0 && pend_fun) { lastfun = pend_name; pend_fun = 0 }
    chain_depth = -1
    i++
  }
}

# Reads the parenthesised clause starting at buf[i] == "(" and returns the
# caught TYPE. Sets G_end to the index just past the closing `)`.
#
# The type is what follows the last top-level `:`, so an annotated multi-line
# clause and a qualified type both parse. `<`/`>` are counted as nesting so a
# generic type argument cannot hide the colon that matters.
function read_param(i,   n, d, start, inner, k, c, colon, dep) {
  n = length(buf); d = 0; start = i
  while (i <= n) {
    c = substr(buf, i, 1)
    if (c == "(") d++
    else if (c == ")") { d--; if (d == 0) { i++; break } }
    i++
  }
  G_end = i
  inner = substr(buf, start + 1, (i - 2) - start)
  colon = 0; dep = 0
  for (k = 1; k <= length(inner); k++) {
    c = substr(inner, k, 1)
    if (c == "(" || c == "<" || c == "[") dep++
    else if (c == ")" || c == ">" || c == "]") dep--
    else if (c == ":" && dep == 0) colon = k
  }
  if (colon == 0) return "?"
  inner = substr(inner, colon + 1)
  gsub(/[ \t\r\n]/, "", inner)
  return inner
}

# Matched on the LAST dotted component, so the bare and both qualified
# spellings all count. Grepping one spelling and missing the other is #667's
# named trap.
function is_cancellation(t) { return (t ~ /(^|\.)CancellationException$/) }
