/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.descriptors

import org.jetbrains.kotlin.backend.common.ir.DeclarationFactory
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.builtins.CompanionObjectMapping.isMappedIntrinsicCompanionObject
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.codegen.descriptors.FileClassDescriptor
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.ir.SourceManager
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrConstructorImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFieldImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrConstructorSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.toIrType
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.load.java.JavaVisibilities
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.PsiSourceManager
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.*

class JvmDeclarationFactory(
    private val psiSourceManager: PsiSourceManager,
    private val builtIns: KotlinBuiltIns
) : DeclarationFactory {
    private val singletonFieldDescriptors = HashMap<IrSymbolOwner, IrField>()
    private val outerThisDescriptors = HashMap<IrClass, IrField>()
    private val innerClassConstructors = HashMap<IrConstructor, IrConstructor>()

    override fun getSymbolForEnumEntry(enumEntry: IrEnumEntry): IrField =
        singletonFieldDescriptors.getOrPut(enumEntry) {
            val symbol = IrFieldSymbolImpl(createEnumEntryFieldDescriptor(enumEntry.descriptor))
            IrFieldImpl(
                enumEntry.startOffset,
                enumEntry.endOffset,
                JvmLoweredDeclarationOrigin.FIELD_FOR_ENUM_ENTRY,
                symbol,
                enumEntry.initializerExpression!!.type
            )
        }

    fun createFileClassDescriptor(fileEntry: SourceManager.FileEntry, packageFragment: PackageFragmentDescriptor): FileClassDescriptor {
        val ktFile = psiSourceManager.getKtFile(fileEntry as PsiSourceManager.PsiFileEntry)
                ?: throw AssertionError("Unexpected file entry: $fileEntry")
        val fileClassInfo = JvmFileClassUtil.getFileClassInfoNoResolve(ktFile)
        val sourceElement = KotlinSourceElement(ktFile)
        return FileClassDescriptorImpl(
            fileClassInfo.fileClassFqName.shortName(), packageFragment,
            listOf(builtIns.anyType),
            sourceElement,
            Annotations.EMPTY // TODO file annotations
        )
    }

    override fun getOuterThisFieldSymbol(innerClass: IrClass): IrField =
        if (!innerClass.isInner) throw AssertionError("Class is not inner: ${innerClass.dump()}")
        else outerThisDescriptors.getOrPut(innerClass) {
            val outerClass = innerClass.parent as? IrClass
                    ?: throw AssertionError("No containing class for inner class ${innerClass.dump()}")

            val symbol = IrFieldSymbolImpl(
                JvmPropertyDescriptorImpl.createFinalField(
                    Name.identifier("this$0"), outerClass.defaultType.toKotlinType(), innerClass.descriptor,
                    Annotations.EMPTY, JavaVisibilities.PACKAGE_VISIBILITY, Opcodes.ACC_SYNTHETIC, SourceElement.NO_SOURCE
                )
            )


                IrFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, DeclarationFactory.FIELD_FOR_OUTER_THIS, symbol, outerClass.defaultType).also {
                    it.parent = innerClass
                }
        }

    override fun getInnerClassConstructorWithOuterThisParameter(innerClassConstructor: IrConstructor): IrConstructor {
        assert((innerClassConstructor.parent as IrClass).isInner) { "Class is not inner: ${(innerClassConstructor.parent as IrClass).dump()}" }

        return innerClassConstructors.getOrPut(innerClassConstructor) {
            createInnerClassConstructorWithOuterThisParameter(innerClassConstructor)
        }
    }

    private fun createInnerClassConstructorWithOuterThisParameter(oldConstructor: IrConstructor): IrConstructor {
        val oldDescriptor = oldConstructor.descriptor
        val classDescriptor = oldDescriptor.containingDeclaration
        val outerThisType = (classDescriptor.containingDeclaration as ClassDescriptor).defaultType

        val newDescriptor = ClassConstructorDescriptorImpl.createSynthesized(
            classDescriptor, oldDescriptor.annotations, oldDescriptor.isPrimary, oldDescriptor.source
        )

        val outerThisValueParameter = newDescriptor.createValueParameter(0, "\$outer", outerThisType)

        val newValueParameters =
            listOf(outerThisValueParameter) +
                    oldDescriptor.valueParameters.map { it.copy(newDescriptor, it.name, it.index + 1) }
        // Call the long version of `initialize()`, because otherwise default implementation inserts
        // an unwanted `dispatchReceiverParameter`
        newDescriptor.initialize(
            oldDescriptor.extensionReceiverParameter?.type,
            null,
            oldDescriptor.typeParameters,
            newValueParameters,
            oldDescriptor.returnType,
            oldDescriptor.modality,
            oldDescriptor.visibility)
        val symbol = IrConstructorSymbolImpl(newDescriptor)
        return IrConstructorImpl(oldConstructor.startOffset, oldConstructor.endOffset, oldConstructor.origin, symbol).also { constructor ->
            newValueParameters.mapIndexedTo(constructor.valueParameters) { i, v ->
                constructor.parent = oldConstructor.parent
                constructor.returnType = oldConstructor.returnType
                if (i == 0) {
                    IrValueParameterImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                        JvmLoweredDeclarationOrigin.FIELD_FOR_OUTER_THIS, IrValueParameterSymbolImpl(v), outerThisType.toIrType()!!, null)
                } else {
                    val oldParameter = oldConstructor.valueParameters[i - 1]
                    IrValueParameterImpl(
                        oldParameter.startOffset, oldParameter.endOffset, oldParameter.origin,
                        IrValueParameterSymbolImpl(v), oldParameter.type, oldParameter.varargElementType
                    ).also {
                        it.defaultValue = oldParameter.defaultValue
                    }
                }
            }
        }
    }


    private fun createEnumEntryFieldDescriptor(enumEntryDescriptor: ClassDescriptor): PropertyDescriptor {
        assert(enumEntryDescriptor.kind == ClassKind.ENUM_ENTRY) { "Should be enum entry: $enumEntryDescriptor" }

        val enumClassDescriptor = enumEntryDescriptor.containingDeclaration as ClassDescriptor
        assert(enumClassDescriptor.kind == ClassKind.ENUM_CLASS) { "Should be enum class: $enumClassDescriptor" }

        return JvmPropertyDescriptorImpl.createStaticVal(
            enumEntryDescriptor.name,
            enumClassDescriptor.defaultType,
            enumClassDescriptor,
            enumEntryDescriptor.annotations,
            Modality.FINAL,
            Visibilities.PUBLIC,
            Opcodes.ACC_ENUM,
            enumEntryDescriptor.source
        )
    }

    override fun getSymbolForObjectInstance(singleton: IrClass): IrField =
        singletonFieldDescriptors.getOrPut(singleton) {
            val symbol = IrFieldSymbolImpl(createObjectInstanceFieldDescriptor(singleton.descriptor))
            return IrFieldImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                JvmLoweredDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE,
                symbol,
                singleton.defaultType
            )
        }

    private fun createObjectInstanceFieldDescriptor(objectDescriptor: ClassDescriptor): PropertyDescriptor {
        assert(objectDescriptor.kind == ClassKind.OBJECT) { "Should be an object: $objectDescriptor" }

        val isNotMappedCompanion = objectDescriptor.isCompanionObject && !isMappedIntrinsicCompanionObject(objectDescriptor)
        val name = if (isNotMappedCompanion) objectDescriptor.name else Name.identifier("INSTANCE")
        val containingDeclaration = if (isNotMappedCompanion) objectDescriptor.containingDeclaration else objectDescriptor
        return PropertyDescriptorImpl.create(
            containingDeclaration,
            Annotations.EMPTY, Modality.FINAL, Visibilities.PUBLIC, false,
            name,
            CallableMemberDescriptor.Kind.SYNTHESIZED, SourceElement.NO_SOURCE, /* lateInit = */ false, /* isConst = */ false,
            /* isExpect = */ false, /* isActual = */ false, /* isExternal = */ false, /* isDelegated = */ false
        ).initialize(objectDescriptor.defaultType)
    }
}