#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$1" && pwd -P)
cli="$repo/wdl-cli/build/install/wdl"
lsp="$repo/wdl-lsp/build/install/wdl-lsp"
test -f "$cli/bin/wdl"
test -f "$lsp/bin/wdl-lsp"
chmod +x "$cli/bin/wdl" "$lsp/bin/wdl-lsp"
config="${XDG_CONFIG_HOME:-$HOME/.config}/wdl"
mkdir -p -- "$config"
# POSIX shell quoting also handles spaces and apostrophes in checkout paths.
quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
{
    printf 'export WDL_HOME=%s\n' "$(quote "$cli")"
    printf 'export WDL_LSP_HOME=%s\n' "$(quote "$lsp")"
    printf '%s\n' 'case ":$PATH:" in *":$WDL_HOME/bin:"*) ;; *) PATH="$WDL_HOME/bin:$PATH" ;; esac'
    printf '%s\n' 'case ":$PATH:" in *":$WDL_LSP_HOME/bin:"*) ;; *) PATH="$WDL_LSP_HOME/bin:$PATH" ;; esac' 'export PATH'
} > "$config/env.sh"
line=". $(quote "$config/env.sh") # wdl environment"
profiles=("$HOME/.profile" "$HOME/.bashrc" "${ZDOTDIR:-$HOME}/.zshrc")
# Bash login shells read only the first existing login profile.
if [[ -f "$HOME/.bash_profile" ]]; then profiles+=("$HOME/.bash_profile");
elif [[ -f "$HOME/.bash_login" ]]; then profiles+=("$HOME/.bash_login"); fi
for profile in "${profiles[@]}"; do
    if ! grep -Fqx -- "$line" "$profile" 2>/dev/null; then printf '\n%s\n' "$line" >> "$profile"; fi
done
printf 'Installed WDL. Restart IDEA / login session. For this shell run:\n%s\nJava 21+ must be available.\n' ". $(quote "$config/env.sh")"
