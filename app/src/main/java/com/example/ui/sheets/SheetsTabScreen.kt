package com.example.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.CyberCopyButton
import com.example.ui.components.CyberTextField
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.TerminalFontFamily

/**
 * Tab 3 - offline command reference.
 *
 * Filtering and search behave as before. The one behaviour change is the
 * category list, now derived from [CheatSheetCatalog.categories] so a new entry
 * can never be unreachable because someone forgot to add its chip.
 */
@Composable
fun SheetsTabScreen(
  search: String,
  onSearchChange: (String) -> Unit,
  category: String,
  onCategoryChange: (String) -> Unit
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

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
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
