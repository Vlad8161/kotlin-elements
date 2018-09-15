package com.tschuchort.kotlinelements

import me.eugeniomarletti.kotlin.metadata.*
import me.eugeniomarletti.kotlin.metadata.jvm.*
import me.eugeniomarletti.kotlin.metadata.shadow.load.java.JvmAbi
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver
import me.eugeniomarletti.kotlin.metadata.shadow.name.NameUtils
import java.util.*
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.TypeElement
import javax.lang.model.element.*
import javax.lang.model.type.TypeMirror

class KotlinTypeElement internal constructor(
		val javaElement: TypeElement,
		metadata: KotlinClassMetadata,
		processingEnv: ProcessingEnvironment
) : KotlinElement(processingEnv), TypeElement by javaElement, KotlinParameterizable, HasKotlinModality, HasKotlinVisibility {

	protected val protoClass: ProtoBuf.Class = metadata.data.classProto
	protected val protoNameResolver: NameResolver = metadata.data.nameResolver
	protected val protoTypeTable: ProtoBuf.TypeTable = protoClass.typeTable

	init {
		val fqName: String = protoNameResolver.getString(protoClass.fqName).replace('/', '.')
		val elementQualifiedName = javaElement.qualifiedName.toString()

		assert(fqName == elementQualifiedName)

		// a Kotlin TypeElement should never have initializers
		assert(javaElement.enclosedElements.none { it.kind == ElementKind.INSTANCE_INIT
												   || it.kind == ElementKind.STATIC_INIT })
	}

	val packageName: String = protoNameResolver.getString(protoClass.fqName).substringBeforeLast('/').replace('/', '.')

	override val visibility: KotlinVisibility = KotlinVisibility.fromProtoBuf(protoClass.visibility!!)

	/**
	 * Whether this is a (possibly anonymous) singleton class of the kind denoted by the `object` keyword
	 */
	val isObject: Boolean = (protoClass.classKind == ProtoBuf.Class.Kind.OBJECT)

	/**
	 * Whether or not the class is an inner class
	 *
	 * A class is an inner class if it has the keyword `inner` i.e. nested classes are not
	 * necessarily inner classes
	 */
	val isInnerClass: Boolean = protoClass.isInnerClass

	/** Whether this class has the `expect` keyword
	 *
	 * An expect class is a class declaration with actual definition in a different
	 * file, akin to a declaration in a header file in C++. They are used in multiplatform
	 * projects where different implementations are needed depending on target platform
	 */
	val isExpectClass: Boolean = protoClass.isExpectClass

	/**
	 * Whether this class has the `external` keyword
	 *
	 * An external class is a class declaration with the actual definition in native
	 * code, similar to the `native` keyword in Java
	 */
	val isExternalClass: Boolean = protoClass.isExternalClass

	val isDataClass: Boolean = protoClass.isDataClass

	/**
	 * class modality
	 * one of: [KotlinModality.FINAL], [KotlinModality.OPEN], [KotlinModality.ABSTRACT], [KotlinModality.ABSTRACT], [KotlinModality.NONE]
	 */
	override val modality: KotlinModality = KotlinModality.fromProtoBuf(protoClass.modality!!)

	/**
	 * the companion object of this element if it has one
	 */
	val companionObject: KotlinTypeElement? by lazy {
		getCompanionSimpleName()?.let { companionSimpleName ->
			val companionElement = javaElement.enclosedElements.single {
						it.kind == ElementKind.CLASS
						&& (it as TypeElement).simpleName.toString() == companionSimpleName
			} as TypeElement

			(companionElement.kotlinMetadata as? KotlinClassMetadata)?.let { metadata ->
				KotlinTypeElement(companionElement, metadata, processingEnv)
			}
			?: throw IllegalStateException(
					"element \"$companionElement\" for companion object with simple name " +
					"\"$companionSimpleName\" of \"$this\" doesn't have KotlinClassMetadata")
		}
	}

	private fun getCompanionSimpleName(): String? =
			if (protoClass.hasCompanionObjectName())
				protoNameResolver.getString(protoClass.companionObjectName)
			else
				null

	override fun getSuperclass(): TypeMirror = TODO("return kotlin superclass")

	override fun getTypeParameters(): List<KotlinTypeParameterElement>
			= protoClass.typeParameterList.zipWith(javaElement.typeParameters) { protoTypeParam, javaTypeParam ->

		if (doTypeParamsMatch(javaTypeParam, protoTypeParam, protoNameResolver))
			KotlinTypeParameterElement(javaTypeParam, protoTypeParam, processingEnv)
		else
			throw AssertionError("Kotlin ProtoBuf.TypeParameters should always " +
								 "match up with Java TypeParameterElements")
	}

	val declaredProperties: List<KotlinPropertyElement> by lazy {
		val methodElems = javaElement.enclosedElements.filter { it.kind == ElementKind.METHOD }
				.castList<ExecutableElement>()

		val fieldElems = javaElement.enclosedElements.filter { it.kind == ElementKind.FIELD }
				.castList<VariableElement>()

		protoClass.propertyList.map { protoProperty ->
			val propertyJvmProtoSignature = protoProperty.jvmPropertySignature!!
			val propertyName = protoNameResolver.getString(protoProperty.name)

			val setterElem = if(propertyJvmProtoSignature.hasSetter()) {
				val setterJvmSignature = propertyJvmProtoSignature.setter.jvmSignatureString(protoNameResolver)
										 ?: throw IllegalStateException("Property setter should always have jvm name if it exists")

				methodElems.atMostOne { it.jvmSignature() == setterJvmSignature }
			}
			else
				null

			//TODO("handle the trick where people use a getter with DeprecationLevel.HIDDEN to create properties with inaccessible getter")
			val getterElem = if(propertyJvmProtoSignature.hasGetter()) {
				val getterJvmSignature = propertyJvmProtoSignature.getter.jvmSignatureString(protoNameResolver)
										 ?: throw IllegalStateException("Property getter should always have jvm name if it exists")

				methodElems.atMostOne { it.jvmSignature() == getterJvmSignature }
			}
			else
				null

			val fieldElem = if(propertyJvmProtoSignature.hasField()) {
				if(propertyJvmProtoSignature.field.hasName())
					throw IllegalStateException("Afaik the field should never have a name in JvmProtoBuf.JvmFieldSignature" +
												"because it must always be the same as the property name")

				fieldElems.atMostOne { it.simpleName.toString() == propertyName }
			}
			else
				null

			/* If the Kotlin property has annotations with target [AnnotationTarget.PROPERTY]
		 	the Kotlin compiler will generate an empty parameterless void-returning
		 	synthetic method named "propertyName$annotations" to hold the annotations that
		 	are targeted at the property and not backing field, getter or setter */
			val syntheticAnnotationHolderElem = if(propertyJvmProtoSignature.hasSyntheticMethod()) {
				val synthJvmSignature = propertyJvmProtoSignature.syntheticMethod.jvmSignatureString(protoNameResolver)
										 ?: throw IllegalStateException("Property synthetic annotation holder method " +
																		"should always have jvm name if it exists")

				methodElems.atMostOne { it.jvmSignature() == synthJvmSignature }
			}
			else
				null

			assert(arrayListOf(fieldElem, setterElem, getterElem).filterNotNull().isNotEmpty())

			KotlinPropertyElement(fieldElem, setterElem, getterElem, syntheticAnnotationHolderElem,
					protoProperty, protoNameResolver, processingEnv)
		}
	}

	val primaryConstructor: KotlinConstructorElement? by lazy {
		constructors.firstOrNull { it.isPrimary }
	}

	/**
	 * Secondary constructors declared within this type element
	 */
	val secondaryConstructors: List<KotlinConstructorElement> by lazy {
		constructors.filter { !it.isPrimary }
	}

	/**
	 * All constructors declared within this type element
	 *
	 * The primary constructor will be the first one in the list
	 */
	val constructors: List<KotlinConstructorElement> by lazy {
		val ctorElems = javaElement.enclosedElements.filter { it.kind == ElementKind.CONSTRUCTOR }
				.castList<ExecutableElement>()

		if(kind == ElementKind.ANNOTATION_TYPE)
			throw UnsupportedOperationException("Constructors of annotation types are unsupported right now")

		protoClass.constructorList.map { protoCtor ->
			val (element, overloadElements) = findCorrespondingExecutableElements(
					protoCtor.jvmSignature(), protoCtor.valueParameterList, ctorElems)

			KotlinConstructorElement(element, overloadElements, protoCtor, protoNameResolver, processingEnv)
		}
				.sortedBy { !it.isPrimary } // sort list by inverse of isPrimary so that the primary ctor will come first
				.also {
					// check that the first ctor really is primary
					assert(it.firstOrNull()?.isPrimary ?: true)

					//check that the second ctor is secondary if there is one, since
					// there may be only one primary ctor
					assert(it.getOrNull(1)?.isPrimary?.not() ?: true)
				}
	}



	/**
	 * methods declared within this class
	 */
	val declaredMethods: List<KotlinFunctionElement> by lazy {
		// a Kotlin TypeElement should never have initializers
		assert(javaElement.enclosedElements.none { it.kind == ElementKind.INSTANCE_INIT
												   || it.kind == ElementKind.STATIC_INIT })

		val methodElems = javaElement.enclosedElements.filter { it.kind == ElementKind.METHOD }
				.castList<ExecutableElement>()

		protoClass.functionList.map { protoMethod ->
			val (element, jvmOverloadElements) = findCorrespondingExecutableElements(
					protoMethod.jvmSignature(), protoMethod.valueParameterList, methodElems)

			KotlinFunctionElement(element, jvmOverloadElements, protoMethod, protoNameResolver, processingEnv)
		}
	}

	/**
	 * used for comparing parameters
	 *
	 * this should really be private in [findCorrespondingExecutableElements] but can't be
	 * because naturally the Kotlin compiler is buggy as hell and will crash (KT-26697)
	 *
	 * Also it can not be a an inner class or _once again_ the compiler will produce garbage
	 * (VerifyError: Bad type on operand stack) 😐🔫
	 */
	private open class JavaParameter(paramElem: VariableElement, val processingEnv: ProcessingEnvironment) {
		val simpleName = paramElem.simpleName.toString()
		val type = paramElem.asType()

		override fun equals(other: Any?) =
				if(other is JavaParameter)
					other.simpleName == simpleName
					&& processingEnv.typeUtils.isSameType(type, other.type)
				else
					false

		override fun hashCode() = Objects.hash(simpleName, type)
	}

	private fun findCorrespondingExecutableElements(protoJvmSignature: String, protoParams: List<ProtoBuf.ValueParameter>,
											executableElements: List<ExecutableElement>) = run { // use `run` for type inference :S
		/*
		When @JvmOverloads was used, there may be multiple executable elements which correspond
		to this Kotlin method/constructor but have different JVM signatures (with only a subset of the parameters)

		First find the the Java executable element that matches the JVM signature of the ProtoBuf method perfectly

		Then find overload elements who must have the same name with a subset of the parameters
		 */

		val matchingElement = executableElements.single { it.jvmSignature() == protoJvmSignature }

		/*
		parameters of the method element and protoMethod should be in the exact same order,
		so we can zip them together.
		But better assert that they have the same name just to be sure
		 */
		val params = matchingElement.parameters.zipWith(protoParams) { paramElem, protoParam ->
			assert(paramElem.simpleName.toString() == protoNameResolver.getString(protoParam.name))

			object : JavaParameter(paramElem, processingEnv) {
				val required = protoParam.declaresDefaultValue
			}
		}

		// now find those other Java executable elements that are generated by @JvmOverloads
		// and belong to this Kotlin method
		val jvmOverloadElems = executableElements.filter { overloadElem ->
			val overloadElemParams = overloadElem.parameters.map { JavaParameter(it, processingEnv) }

			// overload executable element must have...
			overloadElem.simpleName == matchingElement.simpleName // ...the same name
			&& overloadElem.jvmSignature() != protoJvmSignature // ...a different signature, or it would just be the matching element
			&& params.containsAll(overloadElemParams) // ...a subset of the parameters
			&& overloadElemParams.containsAll(params.filter { it.required }) // ...all the required (non-default) parameters
		}

		return@run object {
			val element = matchingElement
			val overloadElements = jvmOverloadElems

			operator fun component1() = element
			operator fun component2() = overloadElements
		}
	}

	//TODO(return kotlin interfaces)
	override fun getInterfaces(): List<TypeMirror> = javaElement.interfaces

	override fun getEnclosedElements(): List<KotlinElement> {
		TODO("KotlinTypeElement enclosed elements")
	}

	override fun getEnclosingElement(): Element?
			= javaElement.enclosingElement.correspondingKotlinElement(processingEnv)
			  ?: javaElement.enclosingElement

	protected fun ProtoBuf.Constructor.jvmSignature() = with(processingEnv.kotlinMetadataUtils) {
		val signature = this@jvmSignature.getJvmConstructorSignature(protoNameResolver, protoTypeTable)
						?: throw IllegalArgumentException("could not get JVM signature for ProtoBuf.Constructor")

		if(this@KotlinTypeElement.kind == ElementKind.ENUM) {
			/* for some reason the Kotlin compiler adds an implicit String and Int argument
			to enum constructors (probably to call it's implicit super constructor
			`Enum::<init>(name: String, ordinal: Int)`). The `ExecutableElement` that is
			the actual constructor won't have those arguments, so we need to remove them
			from the signature so they will match */
			assert(signature.startsWith("<init>(Ljava/lang/String;I"))
			signature.removeFirstOccurance("Ljava/lang/String;I")
		}
		else
			signature
	}

	protected fun ProtoBuf.Function.jvmSignature() = with(processingEnv.kotlinMetadataUtils) {
		this@jvmSignature.getJvmMethodSignature(protoNameResolver)
		?: throw IllegalArgumentException("could not get JVM signature for ProtoBuf.Function")
	}

	protected fun ProtoBuf.Property.fieldJvmSignature() = with(processingEnv.kotlinMetadataUtils) {
		this@fieldJvmSignature.getJvmFieldSignature(protoNameResolver, protoTypeTable)
		?: throw IllegalArgumentException("could not get JVM signature for ProtoBuf.Property field")
	}

	protected fun doFunctionsMatch(functionElement: ExecutableElement, protoFunction: ProtoBuf.Function): Boolean
			= functionElement.jvmSignature() == protoFunction.jvmSignature()

	protected fun doConstructorsMatch(constructorElement: ExecutableElement, protoConstructor: ProtoBuf.Constructor): Boolean
			= constructorElement.jvmSignature() == protoConstructor.jvmSignature()

	/**
	 * returns the mangling suffix added to members with [KotlinVisibility.INTERNAL]
	 */
	private fun getManglingSuffix(): String {
		val moduleName = protoClass.jvmClassModuleName?.let(protoNameResolver::getString) ?: JvmAbi.DEFAULT_MODULE_NAME
		return NameUtils.sanitizeAsJavaIdentifier(moduleName)
	}

	override fun toString() = javaElement.toString()

	override fun hashCode() = javaElement.hashCode()

	override fun equals(other: Any?)
			= (other as? KotlinTypeElement)?.javaElement == javaElement
}