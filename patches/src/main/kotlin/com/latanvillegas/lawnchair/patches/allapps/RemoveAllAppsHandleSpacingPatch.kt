package com.latanvillegas.lawnchair.patches.allapps

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val ALL_APPS_CONTAINER =
    "Lcom/android/launcher3/allapps/ActivityAllAppsContainerView;"

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
        LayoutWithoutSearchContainerFingerprint.method.replaceInstruction(
            handleCallIndex + 1,
            "const/4 v0, 0x0",
        )

        val method = AllAppsSetInsetsFingerprint.method
        val instructions = method.implementation?.instructions
            ?: throw PatchException("Lawnchair All Apps spacing: setInsets has no implementation.")

        // R8 moves this read substantially between Nightlies, so inspect the complete
        // setInsets method instead of a small window around shouldShowAllAppsOnSheet().
        // Match the actual field reference rather than Instruction.toString(), whose
        // representation is not guaranteed to include the referenced field text.
        var patched = false
        for (index in instructions.indices) {
            val candidate = instructions[index]
            if (candidate.opcode.name != "IGET") continue

            val field = (candidate as? ReferenceInstruction)?.reference as? FieldReference
                ?: continue
            if (field.definingClass != "Landroid/graphics/Rect;" ||
                field.name != "top" || field.type != "I") continue

            val destinationRegister = (candidate as? TwoRegisterInstruction)?.registerA
                ?: continue
            method.replaceInstruction(index, "const/4 v$destinationRegister, 0x0")
            patched = true
            break
        }

        if (!patched) {
            throw PatchException(
                "Lawnchair All Apps spacing: Rect.top field read was not found in setInsets.",
            )
        }
    }
}
