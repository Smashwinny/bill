package com.hulk.manualledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original 24-unit artwork. Vector strokes stay sharp at every Android density. */
internal object LedgerIcons {
    private fun glyph(name: String, outline: String, wash: String = ""): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        if (wash.isNotEmpty()) builder.addPath(PathParser().parsePathString(wash).toNodes(),
            fill = SolidColor(Color.Black), fillAlpha = .13f)
        builder.addPath(PathParser().parsePathString(outline).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        return builder.build()
    }
    val Home = glyph("LedgerHome", "M3 10L12 3L21 10 M5 9V19Q5 21 7 21H17Q19 21 19 19V9 M10 21V14H14V21 M9 8H15", "M5 10L12 4L19 10V20H5Z")
    val Receipt = glyph("LedgerReceipt", "M6 3H18V21L15 19L12 21L9 19L6 21Z M9 7H15 M9 11H15 M9 15H12", "M6 3H18V8H6Z")
    val Add = glyph("LedgerAdd", "M8 3H16Q21 3 21 8V16Q21 21 16 21H8Q3 21 3 16V8Q3 3 8 3Z M12 7V17 M7 12H17", "M8 3H16Q21 3 21 8V16Q21 21 16 21H8Q3 21 3 16V8Q3 3 8 3Z")
    val Chart = glyph("LedgerChart", "M4 4V20H21 M8 16V12 M13 16V9 M18 16V5 M7 8L12 5L17 3", "M6 11H10V18H6Z M11 8H15V18H11Z M16 4H20V18H16Z")
    val Settings = glyph("LedgerSettings", "M4 6H20 M4 12H20 M4 18H20 M9 3V9 M16 9V15 M8 15V21", "M7 3H11V9H7Z M14 9H18V15H14Z M6 15H10V21H6Z")
    val Wallet = glyph("LedgerWallet", "M5 6L17 3V6 M5 6H18Q20 6 20 8V18Q20 20 18 20H5Q3 20 3 18V8Q3 6 5 6Z M20 10H15Q13 10 13 12V14Q13 16 15 16H20 M16 13H17", "M14 10H21V16H14Z")
    val Food = glyph("LedgerFood", "M4 3V8Q4 10 7 10Q10 10 10 8V3 M7 3V21 M17 3Q14 7 14 12H19 M19 3V21", "M14 12Q14 6 19 3V12Z")
    val Transit = glyph("LedgerTransit", "M7 3H17Q20 3 20 6V17Q20 19 17 19H7Q4 19 4 17V6Q4 3 7 3Z M4 11H20 M12 6V11 M7 15H8 M16 15H17 M7 19L5 22 M17 19L19 22 M9 6H15", "M5 6H19V11H5Z")
    val Bag = glyph("LedgerBag", "M5 8H19L21 20H3Z M8 9V6Q8 2 12 2Q16 2 16 6V9 M8 13Q12 16 16 13", "M5 8H19L21 20H3Z")
    val Gift = glyph("LedgerGift", "M3 8H21V12H3Z M5 12V21H19V12 M12 8V21 M12 8Q4 8 5 4Q8 0 12 8Q16 0 19 4Q20 8 12 8", "M5 12H19V21H5Z")
    val Heart = glyph("LedgerHeart", "M12 21L4 13Q0 8 4 4Q8 1 12 6Q16 1 20 4Q24 8 20 13Z M7 12H10L12 8L14 15L16 12H18", "M12 21L4 13Q0 8 4 4Q8 1 12 6Q16 1 20 4Q24 8 20 13Z")
    val Leisure = glyph("LedgerLeisure", "M8 6H16Q19 6 20 10L22 17Q22 20 19 20L15 17H9L5 20Q2 20 2 17L4 10Q5 6 8 6Z M7 10V14 M5 12H9 M16 10H16.1 M18 13H18.1 M10 3H14", "M8 6H16Q19 6 20 10L22 17Q22 20 19 20L15 17H9L5 20Q2 20 2 17L4 10Q5 6 8 6Z")
    val Book = glyph("LedgerBook", "M12 5Q7 2 3 4V19Q8 17 12 21Q16 17 21 19V4Q17 2 12 5Z M12 5V21 M6 8L9 9 M15 9L18 8 M6 12L9 13 M15 13L18 12", "M12 5Q17 2 21 4V19Q16 17 12 21Z")
    val Travel = glyph("LedgerTravel", "M10 6V3H14V6 M6 6H18Q20 6 20 8V18Q20 20 18 20H6Q4 20 4 18V8Q4 6 6 6Z M8 6V20 M16 6V20 M7 20V22 M17 20V22", "M8 6H16V20H8Z")
    val Pet = glyph("LedgerPet", "M6 14Q12 6 18 14Q23 21 17 21Q12 18 7 21Q1 21 6 14Z M4 7Q1 5 2 3Q5 1 6 4Q7 7 4 7Z M10 6Q7 5 8 2Q11 0 12 3Q12 6 10 6Z M17 7Q14 7 15 4Q17 1 19 3Q21 5 17 7Z M21 12Q18 12 19 9Q22 7 23 9Q24 11 21 12Z", "M6 14Q12 6 18 14Q23 21 17 21Q12 18 7 21Q1 21 6 14Z")
    val Category = glyph("LedgerCategory", "M3 3H10V10H3Z M14 3H21V10H14Z M3 14H10V21H3Z M17.5 13L22 17.5L17.5 22L13 17.5Z", "M3 3H10V10H3Z M13 17.5L17.5 13L22 17.5L17.5 22Z")
    val Savings = glyph("LedgerSavings", "M6 10Q3 11 3 15Q3 19 7 20V22H10V20H16V22H19V18L22 17V12H19L18 8L15 10 M6 10Q10 8 15 10 M8 13H12 M17 14H17.1 M9 7Q6 7 6 4Q6 1 9 1Q12 1 12 4Q12 7 9 7Z", "M6 10Q3 11 3 15Q3 19 7 20H17Q20 18 22 17V12H19L18 8L15 10Z")
    val Search = glyph("LedgerSearch", "M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10 M15 15L21 21 M6 10Q6 6 10 6", "M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10Z")
    val Trash = glyph("LedgerTrash", "M3 6H21 M9 6V3H15V6 M5 6L6 21H18L19 6 M10 10V17 M14 10V17", "M6 7H18V21H6Z")
    val Sync = glyph("LedgerSync", "M20 9A8 8 0 0 0 6 5L3 8 M3 3V8H8 M4 15A8 8 0 0 0 18 19L21 16 M16 16H21V21")
    val CloudDone = glyph("LedgerCloudDone", "M6 18H5Q1 18 1 14Q1 10 5 10Q5 3 12 3Q18 3 19 9Q23 9 23 14Q23 18 19 18 M9 17L12 20L18 13", "M6 18H5Q1 18 1 14Q1 10 5 10Q5 3 12 3Q18 3 19 9Q23 9 23 14Q23 18 19 18Z")
    val CloudQueue = glyph("LedgerCloudQueue", "M6 18H5Q1 18 1 14Q1 10 5 10Q5 3 12 3Q18 3 19 9Q23 9 23 14Q23 18 19 18 M12 21V12 M8 16L12 12L16 16", "M6 18H5Q1 18 1 14Q1 10 5 10Q5 3 12 3Q18 3 19 9Q23 9 23 14Q23 18 19 18Z")
    val ArrowDown = glyph("LedgerArrowDown", "M12 3V21 M5 14L12 21L19 14")
    val ArrowUp = glyph("LedgerArrowUp", "M12 21V3 M5 10L12 3L19 10")
    val Drag = glyph("LedgerDrag", "M8 5H8.1 M16 5H16.1 M8 12H8.1 M16 12H16.1 M8 19H8.1 M16 19H16.1")
    val all get() = listOf(Home, Receipt, Add, Chart, Settings, Wallet, Food, Transit, Bag, Gift, Heart,
        Leisure, Book, Travel, Pet, Category, Savings, Search, Trash, Sync, CloudDone, CloudQueue, ArrowDown, ArrowUp, Drag)
}
