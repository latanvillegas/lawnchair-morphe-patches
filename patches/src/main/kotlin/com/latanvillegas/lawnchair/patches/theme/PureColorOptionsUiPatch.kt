package com.latanvillegas.lawnchair.patches.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** Finds ColorOptionsKt.<clinit>() structurally across R8-renamed Lawnchair Nightlies. */
private object ColorOptionsClinitFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    custom = custom@ { method, classDef ->
        val listFields = classDef.staticFields.count { it.type == "Ljava/util/List;" }
        val instructions = method.implementation?.instructions?.toList() ?: return@custom false
        val allocations = instructions.count { it.opcode == Opcode.NEW_INSTANCE }
        listFields == 3 && allocations >= 12 && instructions.any { it.opcode == Opcode.NEW_ARRAY }
    },
)

@Suppress("unused")
val pureColorOptionsUiPatch = bytecodePatch(
    name = "Pure black and white color options",
    description = "Shows pure black and pure white as selectable Lawnchair color options.",
) {
    compatibleWith(
        Compatibility(
            name = "Lawnchair Nightly",
            packageName = "app.lawnchair.nightly",
            appIconColor = 0x8BC34A,
        ),
    )

    execute {
        val method = ColorOptionsClinitFingerprint.method
        val instructions = method.instructions

        val customColorType = instructions.firstNotNullOfOrNull { instruction ->
            if (instruction.opcode != Opcode.NEW_INSTANCE) return@firstNotNullOfOrNull null
            ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type
        } ?: throw PatchException("Lawnchair pure colors UI: CustomColor type was not found.")

        // Locate the stock two-entry dynamic ColorOption array structurally.
        val arrayIndex = instructions.indices.firstOrNull { index ->
            if (instructions[index].opcode != Opcode.NEW_ARRAY) return@firstOrNull false
            val ref = instructions[index] as? ReferenceInstruction ?: return@firstOrNull false
            val type = (ref.reference as? TypeReference)?.type ?: return@firstOrNull false
            if (!type.startsWith("[L")) return@firstOrNull false
            val end = minOf(index + 14, instructions.lastIndex)
            instructions.subList(index, end + 1).count { it.opcode == Opcode.APUT_OBJECT } == 2
        } ?: throw PatchException("Lawnchair pure colors UI: dynamic color array was not found.")

        val newArray = instructions[arrayIndex] as? TwoRegisterInstruction
            ?: throw PatchException("Lawnchair pure colors UI: unexpected new-array instruction.")
        val arrayRegister = newArray.registerA
        val sizeRegister = newArray.registerB

        // Different Nightlies can materialize the constant farther away because of R8
        // register scheduling. Search backwards through the whole basic setup region,
        // stopping at the previous array allocation. The last write to sizeRegister wins.
        val lowerBound = (arrayIndex - 1 downTo 0).firstOrNull { instructions[it].opcode == Opcode.NEW_ARRAY }
            ?.plus(1) ?: 0
        val sizeIndex = (arrayIndex - 1 downTo lowerBound).firstOrNull { index ->
            val instruction = instructions[index]
            instruction.opcode.name.startsWith("CONST") &&
                (instruction as? OneRegisterInstruction)?.registerA == sizeRegister
        } ?: throw PatchException("Lawnchair pure colors UI: dynamic array size load was not found.")

        method.replaceInstruction(sizeIndex, "const/4 v$sizeRegister, 0x4")

        val secondAput = instructions.indices.drop(arrayIndex + 1)
            .filter { instructions[it].opcode == Opcode.APUT_OBJECT }
            .take(2)
            .lastOrNull()
            ?: throw PatchException("Lawnchair pure colors UI: stock dynamic entries were not found.")

        val scratchObject = if (arrayRegister == 5 || sizeRegister == 5) 6 else 5
        val scratchValue = if (scratchObject == 6 || arrayRegister == 6 || sizeRegister == 6) 7 else 6

        method.addInstructions(
            secondAput + 1,
            """
                new-instance v$scratchObject, $customColorType
                const v$scratchValue, -0x1000000
                invoke-direct {v$scratchObject, v$scratchValue}, $customColorType-><init>(I)V
                const/4 v$scratchValue, 0x2
                aput-object v$scratchObject, v$arrayRegister, v$scratchValue

                new-instance v$scratchObject, $customColorType
                const/4 v$scratchValue, -0x1
                invoke-direct {v$scratchObject, v$scratchValue}, $customColorType-><init>(I)V
                const/4 v$scratchValue, 0x3
                aput-object v$scratchObject, v$arrayRegister, v$scratchValue

                const/4 v$sizeRegister, 0x2
            """,
        )
    }
}
