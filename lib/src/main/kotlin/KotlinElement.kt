package com.tschuchort.kotlinelements

import me.eugeniomarletti.kotlin.metadata.*
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.*
import javax.tools.Diagnostic

/**
 * An [Element] that has a 1:1 correspondence to an actual syntactic element
 * in the Kotlin source code.
 * That excludes elements that are generated by the Kotlin compiler implicitly
 * for Java-compatibility reasons such fields, getters, setters, interface
 * default implementations and so on
 */
abstract class KotlinElement internal constructor(
		protected val processingEnv: ProcessingEnvironment
) : Element {
	companion object {


		/**
		 * Returns the [NameResolver] of the closest parent element (or this element) that has one
		 */
		internal fun getNameResolver(elem: Element): NameResolver? {
			val metadata = elem.kotlinMetadata
			return when(metadata) {
				is KotlinPackageMetadata -> metadata.data.nameResolver
				is KotlinClassMetadata -> metadata.data.nameResolver
				else -> elem.enclosingElement?.let(::getNameResolver)
			}
		}

		/**
		 * Returns the [KotlinMetadata] of the closest parent element (or this element) that has one
		 */
		internal fun getMetadata(elem: Element): KotlinMetadata?
				= elem.kotlinMetadata ?: elem.enclosingElement?.let(::getMetadata)
	}

	/**
	 * Whether this element is a top level element in Kotlin source code
	 *
	 * Only the location in Kotlin code is considered. Some elements (like free functions or multiple
	 * classes in a single file) are not strictly top level from a Java point of view because the compiler
	 * generates class facades to hold them even though they would appear to be top level in Kotlin
	 *
	 * [PackageElement]s are also considered top level
	 */
	//TODO(make sure isTopLevel handles multifile class facades and all that stuff correctly)
	fun isTopLevel(): Boolean =
			enclosingElement?.run { kind == ElementKind.PACKAGE || kotlinMetadata is KotlinPackageMetadata }
			?: true

	abstract override fun getEnclosedElements(): List<KotlinElement>

	abstract override fun getEnclosingElement(): Element?

	protected fun ExecutableElement.jvmSignature() = getJvmMethodSignature(processingEnv)

	abstract override fun toString(): String
	abstract override fun equals(other: Any?): Boolean
	abstract override fun hashCode(): Int
}

internal fun ExecutableElement.getJvmMethodSignature(processingEnv: ProcessingEnvironment): String
		= with(processingEnv.kotlinMetadataUtils) {
	this@getJvmMethodSignature.jvmMethodSignature
}

/**
 * Whether this element originates from Kotlin code. It doesn't actually have to be
 * a [KotlinElement], it may as well be a [KotlinSyntheticElement] that was generated
 * by the compiler for Java-interop
 */
fun Element.originatesFromKotlinCode(): Boolean = KotlinElement.getNameResolver(this) != null

/**
 * a Java [Element] that was generated by the Kotlin compiler implicitly
 * and doesn't have a 1:1 correspondence with actual syntactic
 * elements in the Kotlin source code
 */
interface KotlinSyntheticElement : Element

fun Element.correspondingKotlinElement(processingEnv: ProcessingEnvironment): KotlinElement? {
	return if(this is KotlinElement)
		this
	else if(!originatesFromKotlinCode())
		null
	else when (kind) {
		ElementKind.CLASS,
		ElementKind.ENUM,
		ElementKind.INTERFACE,
		ElementKind.ANNOTATION_TYPE -> with(this as TypeElement) {
			val metadata = kotlinMetadata

			when {
				metadata is KotlinClassMetadata -> KotlinTypeElement(this, metadata, processingEnv)
				metadata is KotlinFileMetadata -> TODO("Kotlin file element")
				this.isInterfaceDefaultImpl(processingEnv) -> TODO("Kotlin Interface DefaultImpl")
				else -> throw AssertionError("Element originates from Kotlin code and is type-kind but not a KotlinTypeElement, " +
											 "KotlinFileElement or Kotlin Interface DefaultImpl")
			}
		}

		ElementKind.CONSTRUCTOR ->
			(enclosingElement as KotlinTypeElement).constructors.single {
				it.javaElement == this || it.jvmOverloadElements.any { it == this }
			}


		ElementKind.METHOD -> with(this as ExecutableElement) {
			val enclosingElem = (enclosingElement.correspondingKotlinElement(processingEnv) as KotlinTypeElement)

			if(this.maybeKotlinSetter())
				enclosingElem.declaredProperties.single { it.javaSetterElement == this }
			else if(this.maybeKotlinSetter())
				enclosingElem.declaredProperties.single { it.javaGetterElement == this }
			else if(this.maybeSyntheticPropertyAnnotHolder())
				enclosingElem.declaredProperties.single { it.javaSyntheticAnnotationHolderElement == this }
			else
				enclosingElem.declaredMethods.single {
					it.javaElement == this || it.jvmOverloadElements.any { it == this }
				}
			as KotlinElement // unnecessary cast here because the compiler is going crazy again
							// and would rather infer one of the less specific shared interfaces
		}

		ElementKind.INSTANCE_INIT,
		ElementKind.STATIC_INIT -> throw AssertionError(
				"element originating from Kotlin code should never be of kind INSTANCE_INIT or STATIC_INIT"
		)

		ElementKind.TYPE_PARAMETER -> {
			val enclosingElem = enclosingElement.correspondingKotlinElement(processingEnv)

			if(enclosingElem is KotlinParameterizable)
				enclosingElem.typeParameters.single { it.javaElement == this }
			else
				throw AssertionError("enclosing element of TYPE_PARAMETER element originating " +
									 "from Kotlin is not KotlinParameterizable")
		}

		ElementKind.PARAMETER -> {
			(enclosingElement.correspondingKotlinElement(processingEnv) as KotlinExecutableElement)
					.parameters.single { it.simpleName == simpleName }
		}

		ElementKind.FIELD ->
			(enclosingElement.correspondingKotlinElement(processingEnv) as KotlinTypeElement)
					.declaredProperties.single { it.javaFieldElement == this }

		ElementKind.ENUM_CONSTANT -> TODO("handle ElementKind.ENUM_CONSTANT")

		ElementKind.RESOURCE_VARIABLE,
		ElementKind.EXCEPTION_PARAMETER,
		ElementKind.LOCAL_VARIABLE -> throw AssertionError(
				"Element to be converted is local but this library was written under the assumption " +
				"that it is impossible to get a local Element during annotation processing (which will probably " +
				"change in the future)"
		)

		ElementKind.MODULE -> TODO("handle module elements gracefully")

		ElementKind.PACKAGE -> TODO("implement kotlin package element")

		ElementKind.OTHER -> throw UnsupportedOperationException(
				"Can not convert element \"$this\" of unsupported kind \"$kind\" to KotlinSyntacticElement")

		null -> throw NullPointerException("Can not convert to KotlinSyntacticElement: kind of element \"$this\" was null")

		else -> throw UnsupportedOperationException(
				"Can not convert element \"$this\" of unsupported kind \"$kind\" to KotlinSyntacticElement.\n" +
				"Element ElementKind was probably added to the Java language at a later date")
	}
}

fun TypeElement.isInterfaceDefaultImpl(processingEnv: ProcessingEnvironment): Boolean {
	val enclosingTypeElem =  enclosingElement?.correspondingKotlinElement(processingEnv) as? KotlinTypeElement

	return (qualifiedName.toString() == enclosingTypeElem?.qualifiedName.toString() + ".DefaultImpl"
			&& enclosingTypeElem?.kind == ElementKind.INTERFACE)
}