package com.example.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.CyberBackHeader
import com.example.ui.components.CyberCopyButton
import com.example.ui.components.CyberCardAction
import com.example.ui.components.CyberMessage
import com.example.ui.components.CyberSectionLabel
import com.example.ui.components.CyberTerminalBox
import com.example.ui.components.CyberTextField
import com.example.ui.shell.LocalShell
import com.example.ui.shell.VirtualFileSystem
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.TerminalFontFamily

/**
 * Tab 3 - offline command reference.
 *
 * Filtering and search behave as before. The one behaviour change is the
 * category list, now derived from [CheatSheetCatalog.categories] so a new entry
 * can never be unreachable because someone forgot to add its chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetsTabScreen(
  search: String,
  onSearchChange: (String) -> Unit,
  category: String,
  onCategoryChange: (String) -> Unit,
  sshEnabled: Boolean = false,
) {
  val palette = LocalCyberPalette.current
  val allLabel = stringResource(R.string.sheets_filter_all)

  val filtered = remember(search, category) {
    CheatSheetCatalog.commands.filter { command ->
      val matchesCategory = category == CheatSheetCatalog.ALL_CATEGORY ||
        command.category == category
      val matchesQuery = search.isBlank() ||
        command.title.contains(search, ignoreCase = true) ||
        command.command.contains(search, ignoreCase = true)
      matchesCategory && matchesQuery
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .testTag("sheets_tab"),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    CyberMessage(
      message = stringResource(R.string.sheets_reference_note),
      isError = false
    )

    CyberTextField(
      value = search,
      onValueChange = onSearchChange,
      leadingIcon = {
        Icon(
          Icons.Default.Search,
          contentDescription = null,
          tint = palette.mutedForeground,
          modifier = Modifier.size(20.dp)
        )
      },
      placeholder = stringResource(R.string.sheets_search_placeholder),
      testTag = "cheat_search"
    )

    // Single scrolling line, as it has always been.
    //
    // A FlowRow was tried here to show all eight chips at once. It was the wrong
    // trade on this screen: wrapping to a second line costs vertical space, and
    // the list below is what needs height, not the chips. One line, swipe to see
    // the rest.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .testTag("cheat_categories"),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      CheatSheetCatalog.categories.forEach { candidate ->
        // The sentinel is a key, not display text: localise it at the edge.
        val display = if (candidate == CheatSheetCatalog.ALL_CATEGORY) allLabel else candidate
        val isSelected = category == candidate
        FilterChip(
          selected = isSelected,
          onClick = { onCategoryChange(candidate) },
          label = {
            Text(
              text = display,
              fontSize = 13.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
          },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = palette.primaryDim,
            selectedLabelColor = palette.primary,
            containerColor = palette.surface,
            labelColor = palette.mutedForeground
          ),
          border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = isSelected,
            borderColor = if (isSelected) palette.primary else palette.border
          )
        )
      }
    }

    if (filtered.isEmpty()) {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = stringResource(R.string.sheets_no_results),
          color = palette.mutedForeground,
          fontSize = 14.5.sp
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .testTag("cheat_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(filtered, key = { "${it.category}/${it.title}" }) { command ->
          CheatCommandCard(command)
        }
      }
    }

    // Nothing is appended below the list on purpose.
    //
    // This Column does not scroll; the list holds the only `weight(1f)`, so any
    // unweighted sibling is measured first and steals height from it. Three
    // iterations got this wrong in three directions - the shell inside each card,
    // then beside the list, then as two cards at the bottom - and the command
    // reference, the reason this tab exists, was squeezed out every time.
    //
    // The lab shell and the SSH client are opened from the top app bar instead,
    // where they cost no vertical space at all.
  }
}

@Composable
private fun CheatCommandCard(command: CheatCommand) {
  val palette = LocalCyberPalette.current
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = palette.surface),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, palette.border)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)
        ) {
          Text(
            text = command.category.uppercase(),
            color = palette.primary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = TerminalFontFamily
          )
          Text(
            text = command.title,
            color = palette.foreground,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
        }
        CyberCopyButton(textToCopy = command.command)
      }
      Spacer(Modifier.height(8.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(palette.terminalBg)
          .border(0.5.dp, palette.terminalBorder, RoundedCornerShape(8.dp))
          .padding(10.dp)
      ) {
        SelectionContainer {
          Text(
            text = "\$ ${command.command}",
            color = palette.primary,
            fontFamily = TerminalFontFamily,
            fontSize = 13.5.sp,
            lineHeight = 20.sp
          )
        }
      }
    }
  }
}

/**
 * The sandboxed lab shell, on its own screen.
 *
 * It used to be appended under the cheat sheet list. That could not work: the list
 * holds the tab's only `weight(1f)`, so anything unweighted below it is measured
 * first and the reference - the reason the tab exists - was squeezed to nothing.
 * A separate route gives the shell a full screen, which is also the only way a
 * terminal gets enough height for its transcript and its prompt.
 */
/** Prompt shown inside the transcript, matching the seeded `CyberTerminalBox` look. */
private const val PROMPT = "\$ "

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabShellScreen(onBack: () -> Unit) {
  val palette = LocalCyberPalette.current
  val keyboard = LocalSoftwareKeyboardController.current
  val filesystem = remember { VirtualFileSystem() }

  // The transcript is the shell's own memory. Held in a list so appending a line
  // does not re-parse the whole scrollback.
  val transcript = remember { mutableStateListOf<String>() }
  var input by remember { mutableStateOf("") }

  fun run(line: String) {
    val result = LocalShell.execute(line, filesystem, System.currentTimeMillis())
    if (result.clearScreen) {
      transcript.clear()
      return
    }
    // A blank line submits nothing and leaves the transcript alone, which is what
    // pressing return on an empty prompt should feel like.
    if (line.isBlank()) return
    transcript.add("\$ $line")
    if (result.output.isNotEmpty()) {
      transcript.add(result.output)
    }
    if (result.failed) transcript.add("[exit ${result.status}]")
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    CyberBackHeader(title = stringResource(R.string.sheets_shell_label), onBack = onBack)

    // Three plain-language blocks before any terminal chrome. The complaint that
    // this screen made no sense was correct: it opened on a black box with a
    // prompt and no statement of what it could or could not do. An operator who
    // does not know it is a simulator will read "no route to host" as a fact about
    // their network.
    CyberMessage(
      title = stringResource(R.string.lab_what_title),
      message = stringResource(R.string.lab_what_body),
      isError = false
    )
    CyberMessage(
      title = stringResource(R.string.lab_safety_title),
      message = stringResource(R.string.lab_safety_body),
      isError = false
    )
    CyberMessage(
      title = stringResource(R.string.lab_try_title),
      message = stringResource(R.string.lab_try_body),
      isError = false
    )

    CyberTerminalBox(
      content = buildString {
        if (transcript.isEmpty()) appendLine(stringResource(R.string.sheets_shell_welcome))
        transcript.forEach { appendLine(it) }
        // The prompt plus what has been typed so far, inside the same box.
        // This is the fix for the "I type in a separate field" complaint: a
        // terminal that does not show the line you are writing is not a terminal.
        append("${PROMPT}$input")
      },
      minHeight = 140
    )

    Spacer(Modifier.height(8.dp))

    // Visually hidden, still the real input target: the caret and selection live
    // in the box above, this only exists to receive keystrokes. `CyberTextField`
    // brings itself into view, which is what keeps the box on screen when the IME
    // opens - the transparent box is anchored to the same place.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .alpha(0f)
        .testTag("lab_shell_input")
    ) {
      BasicTextField(
        value = input,
        onValueChange = { input = it },
        singleLine = true,
        textStyle = TextStyle(
          color = Color.Transparent,
          fontFamily = TerminalFontFamily,
          fontSize = 1.sp
        ),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(
          onGo = {
            val line = input
            input = ""
            keyboard?.hide()
            run(line)
          }
        ),
        modifier = Modifier.fillMaxWidth()
      )
    }

    // Tapping the transcript focuses the invisible field, so the box behaves like
    // a real terminal: tap where you want to type.
    Spacer(
      modifier = Modifier
        .fillMaxWidth()
        .height(0.dp)
    )

    Spacer(Modifier.height(6.dp))

    // Wraps: five chips plus a long one ("nmap 10.10.14.5") overflowed a fixed Row
    // and the rightmost shortcut was simply clipped off the screen.
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      listOf("help", "pwd", "ls", "nmap 10.10.14.5", "whoami").forEach { suggestion ->
        Card(
          onClick = { run(suggestion) },
          colors = CardDefaults.cardColors(containerColor = palette.surfaceRaised),
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, palette.border),
          modifier = Modifier.testTag("shell_quick_$suggestion")
        ) {
          Text(
            text = suggestion,
            color = palette.primary,
            fontFamily = TerminalFontFamily,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
          )
        }
      }
    }
  }
}

/**
 * Sub-screens pushed on top of the Cheat Sheet tab.
 *
 * Modelled as a sealed set of two rather than a route string: the compiler then
 * refuses a typo, and adding a third tool forces the `when` in MainActivity to
 * handle it instead of silently falling through.
 */
enum class SubScreen { LAB, SSH }
