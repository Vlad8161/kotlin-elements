package com.tschuchort.kotlinelements

import me.eugeniomarletti.kotlin.metadata.*
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.*
import javax.lang.model.type.TypeMirror

/**
 * An [Element] that originates from Kotlin code
 */
sealed class KotlinElement(
		private val element: Element, protected val processingEnv: ProcessingEnvironment
) : Element by element {

	protected val typeUtils = processingEnv.typeUtils
	protected val elementUtils = processingEnv.elementUtils

	companion object {
		fun get(element: Element, processingEnv: ProcessingEnvironment): KotlinElement?
				= element as? KotlinElement
				  ?: (KotlinSyntacticElement.get(element, processingEnv)
					  ?: TODO("handle implicit kotlin elements"))

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
	val isTopLevel =
			enclosingElement?.run { kind == ElementKind.PACKAGE || kotlinMetadata is KotlinPackageMetadata }
			?: true

	override fun getEnclosedElements(): List<KotlinElement>
			= element.enclosedElements.map { enclosedElem ->
		KotlinElement.get(enclosedElem, processingEnv)
		?: throw IllegalStateException(
				"Can not convert enclosed element \"$enclosedElem\" with kind \"${enclosedElem.kind}\" " +
				"of KotlinElement \"$this\" to KotlinElement but " +
				"all enclosed elements of a kotlin element should also be kotlin elements")
	}

	protected fun ExecutableElement.jvmSignature() = with(processingEnv.kotlinMetadataUtils) {
		this@jvmSignature.jvmMethodSignature
	}

	// override to correct return type to nullable
	override fun getEnclosingElement(): Element? = element.enclosingElement

	override fun toString() = element.toString()
	override fun equals(other: Any?) = element.equals(other)
	override fun hashCode() = element.hashCode()
}

fun Element.isKotlinElement() = KotlinElement.getNameResolver(this) != null

/**
 * returns this element as a subtype of [KotlinSyntacticElement] or null if it's not a Kotlin element
 */
fun Element.toKotlinElement(processingEnv: ProcessingEnvironment): KotlinElement?
		= KotlinElement.get(this, processingEnv)

/**
 * a Java [Element] that was generated by the Kotlin compiler implicitly
 * and doesn't have a 1:1 correspondence with actual syntactic
 * elements in the Kotlin source code
 */
abstract class KotlinImplicitElement internal constructor(
		element: Element, processingEnv: ProcessingEnvironment
) : KotlinElement(element, processingEnv) {
	companion object {
		fun get(element: Element, processingEnv: ProcessingEnvironment): KotlinImplicitElement? {
			val enclosingElem = KotlinSyntacticElement.get(element.enclosingElement, processingEnv) as? KotlinTypeElement

			if((element as? TypeElement)?.qualifiedName?.toString() == enclosingElem?.qualifiedName?.toString() + ".DefaultImpl"
					  && enclosingElem?.kind == ElementKind.INTERFACE) {
				return KotlinInterfaceDefaultImpls(element, enclosingElem, processingEnv)
			}

			private fun getDefaultImpls(): KotlinInterfaceDefaultImpls?
		}
	}
}

/**
 * An element that has a 1:1 correspondence to an actual syntactic element
 * in the Kotlin source code.
 * That excludes elements that are generated by the Kotlin compiler implicitly
 * for Java-compatibility reasons such fields, getters, setters, interface
 * default implementations and so on
 */
abstract class KotlinSyntacticElement internal constructor(
		private val element: Element, processingEnv: ProcessingEnvironment
) : KotlinElement(element, processingEnv) {

	companion object {
		fun get(element: Element, processingEnv: ProcessingEnvironment): KotlinSyntacticElement? =
				if(element is KotlinSyntacticElement)
					element
				else when (element.kind) {
					ElementKind.PACKAGE -> KotlinPackageElement.get(element as PackageElement, processingEnv)

					ElementKind.CLASS, ElementKind.ENUM,
					ElementKind.INTERFACE, ElementKind.ANNOTATION_TYPE ->
						KotlinTypeElement.get(element as TypeElement, processingEnv)

					ElementKind.METHOD, ElementKind.CONSTRUCTOR,
					ElementKind.INSTANCE_INIT, ElementKind.STATIC_INIT ->
						KotlinExecutableElement.get(element as ExecutableElement, processingEnv)

					ElementKind.TYPE_PARAMETER ->
						KotlinTypeParameterElement.get(element as TypeParameterElement, processingEnv)

					ElementKind.FIELD, ElementKind.ENUM_CONSTANT, ElementKind.PARAMETER,
					ElementKind.LOCAL_VARIABLE, ElementKind.RESOURCE_VARIABLE,
					ElementKind.EXCEPTION_PARAMETER -> TODO("implement KotlinVariableElement")

					ElementKind.MODULE -> TODO("handle module elements gracefully")

					ElementKind.OTHER -> throw UnsupportedOperationException(
							"Can not convert element \"$element\" of unsupported kind \"${element.kind}\" to KotlinSyntacticElement")

					null -> throw NullPointerException("Can not convert to KotlinSyntacticElement: kind of element \"$element\" was null")

					else -> throw UnsupportedOperationException(
							"Can not convert element \"$element\" of unsupported kind \"${element.kind}\" to KotlinSyntacticElement.\n" +
							"Element ElementKind was probably added to the Java language at a later date")
				}
	}
}
