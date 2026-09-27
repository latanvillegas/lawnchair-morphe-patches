package com.latanvillegas.lawnchair.patches.allapps

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch

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

@Suppress("unused")
val removeAllAppsHandleSpacingPatch = bytecodePatch(
    name = "Remove All Apps handle spacing",
    description = "Removes the top spacing reserved for the All Apps drag handle.",
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

        // This is the verified handle-spacing modification. Newer Nightlies such as
        // #5171 do not expose the old allAppsPadding.top read in setInsets(), so do
        // not fail the entire patch set trying to modify an instruction that is not
        // present in that build.
        LayoutWithoutSearchContainerFingerprint.method.replaceInstruction(
            handleCallIndex + 1,
            "const/4 v0, 0x0",
        )
    }
}
