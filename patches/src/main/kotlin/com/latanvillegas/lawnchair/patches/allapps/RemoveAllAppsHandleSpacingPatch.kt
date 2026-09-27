package com.latanvillegas.lawnchair.patches.allapps

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction

private const val ALL_APPS_CONTAINER =
    "Lcom/android/launcher3/allapps/ActivityAllAppsContainerView;"

/**
 * Removes the extra top space in All Apps.
 *
 * There are two independent sources of top spacing in current Lawnchair builds:
 * 1. layoutWithoutSearchContainer() reserves room for the bottom-sheet handle.
 * 2. setInsets() uses DeviceProfile.allAppsPadding.top for the All Apps content.
 *
 * The first fingerprint forces the handle-specific branch to use zero margin.
 * The second forces the top padding passed to the All Apps container to zero while
 * leaving the horizontal and bottom padding logic untouched.
 */
private object LayoutWithoutSearchContainerFingerprint : Fingerprint(
    definingClass = ALL_APPS_CONTAINER,
    name = "layoutWithoutSearchContainer",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/launcher3/DeviceProfile;",
            name = "shouldShowAllAppsOnSheet",
            parameters = emptyList(),
            returnType = "Z",
        ),
    ),
)

private object AllAppsSetInsetsFingerprint : Fingerprint(
    definingClass = ALL_APPS_CONTAINER,
    name = "setInsets",
    returnType = "V",
    parameters = listOf("Landroid/graphics/Rect;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/launcher3/DeviceProfile;",
            name = "shouldShowAllAppsOnSheet",
            parameters = emptyList(),
            returnType = "Z",
        ),
    ),
)

@Suppress("unused")
val removeAllAppsHandleSpacingPatch = bytecodePatch(
    name = "Remove All Apps handle spacing",
    description = "Removes the handle spacing and remaining top padding from All Apps.",
) {
    compatibleWith(
        Compatibility(
            name = "Lawnchair Nightly",
            packageName = "app.lawnchair.nightly",
            appIconColor = 0x8BC34A,
        ),
    )

    execute {
        val handleCallIndex =
            LayoutWithoutSearchContainerFingerprint.instructionMatches.first().index

        // Force the handle-specific zero-margin path.
        LayoutWithoutSearchContainerFingerprint.method.replaceInstruction(
            handleCallIndex + 1,
            "const/4 v0, 0x0",
        )

        val sheetCallIndex = AllAppsSetInsetsFingerprint.instructionMatches.first().index

        // setInsets() reads allAppsPadding.top immediately around the sheet decision.
        // Zero the destination register of the Rect.top IGET without touching the
        // horizontal or bottom padding logic.
        val method = AllAppsSetInsetsFingerprint.method
        val instructions = method.implementation!!.instructions
        val start = (sheetCallIndex - 12).coerceAtLeast(0)
        val end = (sheetCallIndex + 20).coerceAtMost(instructions.lastIndex)

        var patched = false
        for (index in start..end) {
            val instruction = instructions[index]
            if (instruction.opcode.name == "IGET_OBJECT") {
                for (next in (index + 1)..minOf(index + 5, end)) {
                    val candidate = instructions[next]
                    if (candidate.opcode.name == "IGET" &&
                        candidate.toString().contains("Landroid/graphics/Rect;->top:I")) {
                        val destinationRegister =
                            (candidate as TwoRegisterInstruction).registerA
                        method.replaceInstruction(
                            next,
                            "const/4 v$destinationRegister, 0x0",
                        )
                        patched = true
                        break
                    }
                }
            }
            if (patched) break
        }

        if (!patched) {
            throw app.morphe.patcher.patch.PatchException(
                "Lawnchair All Apps spacing: allAppsPadding.top load was not found.",
            )
        }
    }
}
