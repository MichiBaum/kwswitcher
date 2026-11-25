# Search &amp; Window Filtering

%product% (`%cmd_name%`) pairs strict desktop window hygiene with a fast, multi-token fuzzy search engine (`WindowFilter`) capable of ranking 100+ open windows in well under 5 ms.

## Multi-Token Query Matching

When you type in the search field, `WindowFilter` trims leading/trailing whitespace and splits the query on spaces into independent, case-insensitive tokens:

* **AND across tokens:** *Every* token in your query must match for a window to remain in the results list.
* **OR across fields:** Each individual token can match either the **window title** or the **process name** (executable name without `.exe`).
* **Cross-field bonus:** When a token matches *both* the window title and the process name, `%cmd_name%` takes the higher of the two field scores and adds a **`+40`** bonus.

### Example Queries

| Query | Matches | Why It Works |
| :--- | :--- | :--- |
| `chrome pr` | `Pull Request #42 - Google Chrome` (`chrome`) | Token `chrome` matches the process name (and title); token `pr` matches the acronym **P**ull **R**equest. |
| `ij kws` | `kwswitcher – Main.kt` (`idea64`) or `IntelliJ IDEA` | `ij` matches camelCase/word boundaries or subsequence, while `kws` matches the `kwswitcher` prefix. |
| `term log` | `Server Logs` (`WindowsTerminal`) | `term` matches a word boundary (`Windows` + `Terminal`) in the process name; `log` matches `Logs` in the title. |

## Ranking Tiers &amp; Scoring

Each query token is scored independently against the window title and process name using five ordered tiers (highest applicable tier wins per field), and token scores are summed across the query:

| Tier | Match Type | Base Score &amp; Bonuses | Example (`token` -> `Target`) |
| :--- | :--- | :--- | :--- |
| **1** | **Exact Prefix** | `1000` + length bonus (`0..100`, favoring shorter/tighter strings) | `code` -> `Code.exe`, `slack` -> `Slack` |
| **2a** | **Word-Boundary / CamelCase Prefix** | `750` + position bonus (`0..80`, favoring earlier word boundaries) | `term` -> `WindowsTerminal`, `switch` -> `Kotlin Switcheroo` |
| **2b** | **Acronym Sequence** | `650` *(tokens of 2+ characters matching word-start characters in order)* | `ij` -> `IntelliJ`, `ps` -> `PowerShell`, `vs` -> `Visual Studio` |
| **3** | **Contiguous Substring** | `400` + position bonus (`0..80`, favoring earlier occurrences) | `switch` -> `kwswitcher` |
| **4** | **Compact Fuzzy Subsequence** | `120` + `25` per consecutive char pair + `20` per boundary hit + `0..40` compactness bonus | `wfltr` -> `WindowFilter.kt` |

### Word Boundaries, Noise Rejection &amp; Tie-Breaking

* **Word-boundary detection:** Index `0`, any letter or digit immediately following punctuation/whitespace, `lowerCase` -> `UpperCase` camelCase transitions, and `Letter` -> `Digit` transitions all count as word boundaries.
* **Scattered noise rejection:** In Tier 4 (fuzzy subsequence), if the matched characters are spread across a span wider than `token.length * 4 + 4`, the match is rejected so short 1–2 character tokens do not match random unrelated long window titles.
* **Z-order tie-breaker:** Whenever two windows achieve the exact same score (including an empty search query where all windows score `0`), `%cmd_name%` preserves their native Win32 **Z-order** so your most recently active window always ranks first.

## Highlighted Match Spans

`WindowFilter` returns the exact character index ranges matched in both the window title and process badge, merging adjacent or overlapping ranges automatically:

* When a token matches only one field, that field's matched characters are highlighted in bold accent color.
* When a token matches *both* fields, the higher-scoring field is always highlighted, and the secondary field is also highlighted if it achieved at least a contiguous substring match (`score >= 400`).

## Automatic Window Filtering Rules

Before search scoring runs, `WindowEnumerator` walks top-level windows on the **current virtual desktop** via `EnumWindows` and automatically excludes non-interactive shell clutter:

1. **Invisible or disabled windows:** Excluded when `IsWindowVisible` or `IsWindowEnabled` returns `0` (for example, background helper HWNDs or modal-blocked parent frames).
2. **Tool &amp; non-activatable windows:** Excluded when `GWL_EXSTYLE` has `WS_EX_NOACTIVATE`, or has `WS_EX_TOOLWINDOW` without `WS_EX_APPWINDOW`.
3. **DWM-cloaked &amp; other-desktop windows:** Excluded when `DwmGetWindowAttribute(DWMWA_CLOAKED)` is non-zero—filtering out suspended UWP background frames and windows located on *other* Windows virtual desktops.
4. **Untitled windows:** Excluded when `GetWindowTextW` is empty or whitespace-only.
5. **Self-exclusion:** `%cmd_name%`'s own `Kotlin Switcheroo Overlay` dialog is always excluded.
6. **User ignore rules:** Any window whose process name matches `ignoredProcesses` or whose title matches a regex in `ignoredTitlePatterns` in `%\APPDATA%\kwswitcher\config.json` is excluded.

<seealso>
    <category ref="user-guide">
        <a href="usage-and-shortcuts.md">Usage &amp; Keyboard Shortcuts</a>
        <a href="configuration.md">Configuration Reference</a>
    </category>
    <category ref="developer-guide">
        <a href="architecture.md">System Architecture</a>
    </category>
</seealso>
