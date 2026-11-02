# AI Policy

Short version: AI-assisted code is welcome, shipped code is still yours.

## For contributors

You can use AI tools to write, review or explain code in this repo. The bar is
the same as for human-written code:

- You are responsible for what you submit. If a model hallucinated an API that
  does not exist, the PR that ships it is yours, not the model's.
- Understand what you paste. If you cannot explain the diff line by line, you
  are not ready to open the PR.
- No pasting secrets, tokens or personal data into prompts.
- If a change is AI-generated, say so in the PR description. It helps review:
  reviewers look harder at code the author did not fully write.

## For maintainers

AI is used here the same way: as a fast first pass, not as a final authority.
Model output gets the same review as anything else, and version-related
claims (Minecraft class names, API signatures, dependency versions) are always
verified against the actual jars and build output before merge.

## For the codebase

- Keep comments honest. Do not leave AI-flavored filler ("comprehensive",
  "robust", "seamless") in code or docs. Say what the code does.
- New public surface is a design decision, not a completion of a prompt. If a
  tool "suggested" a new dependency, option or abstraction, the PR must justify
  it, because the default answer here is usually no.
