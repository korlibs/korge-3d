package korlibs.korge3d.format.gltf2

import korlibs.datastructure.iterators.*
import korlibs.graphics.*
import korlibs.graphics.shader.*
import korlibs.korge3d.*
import korlibs.korge3d.material.*
import korlibs.math.geom.*
import korlibs.memory.*
import korlibs.time.*

fun Container3D.gltf2View(gltf: GLTF2) = GLTF2View(gltf).addTo(this)

class GLTF2ViewSkin(
    val gltf: GLTF2,
    val skin: GLTF2.Skin,
    val view: GLTF2View,
) {
    val inverseBindMatrices = GLTF2AccessorVectorMAT4(gltf.accessors[skin.inverseBindMatrices].accessor(gltf))

    fun getJoints(): Array<Matrix4> {
        return (0 until skin.joints.size).map { n ->
            val jointId = skin.joints[n]
            val viewNode = view.nodeToViews[gltf.nodes[jointId]]!!
            viewNode.transform.matrix.immutable * inverseBindMatrices[jointId]
        }.toTypedArray()
    }

    fun putUniforms(ctx: RenderContext3D) {
        ctx.rctx[Shaders3D.Bones4UB].push {
            it[u_BoneMats] = getJoints()
        }
    }
}

class GLTF2View(override var gltf: GLTF2, autoAnimate: Boolean = true) : Container3D(), GLTF2Holder {
    val nodeToViews = LinkedHashMap<GLTF2.Node, GLTF2ViewNode>()
    val skinsToView =  LinkedHashMap<GLTF2.Skin, GLTF2ViewSkin>()

    fun addNode(node: GLTF2.Node): GLTF2ViewNode {
        val view = GLTF2ViewNode(gltf, node, this)
        addChild(view)
        return view
    }

    init {
        for (skin in gltf.skins) {
            val vskin = GLTF2ViewSkin(gltf, skin, this)
            addNode(skin.skeletonNode(gltf))
            //println("SKIN: $skin")
            skinsToView[skin] = vskin
        }

        for (scene in gltf.scenes) {
            for (node in scene.childrenNodes) {
                addNode(node)
            }
        }

        if (autoAnimate) {
            addUpdater(first = false) {
                //println("updater!")
                updateAnimationDelta(it)
            }
        }
    }

    var currentTime = 0.seconds
    fun updateAnimationDelta(dt: TimeSpan) {
        currentTime += dt
        updateAnimation(currentTime)
    }

    fun updateAnimation(time: TimeSpan) {
        for (animation in gltf.animations) {
            for (channel in animation.channels) {
                val target = channel.target ?: continue
                val node = gltf.nodes[target.node]
                val view = nodeToViews[node] ?: continue
                val sampler = animation.samplers[channel.sampler]
                val maxTime = sampler.maxTime(gltf)
                val currentTime = time.seconds.toFloat()
                val rtime = currentTime % maxTime

                //println("channel=$channel : ${sampler.times(gltf).toFloatArray().toList()}")

                when (target.path) {
                    GLTF2.Animation.Channel.TargetPath.WEIGHTS -> {
                        view.meshView?.primitiveViews?.fastForEach {
                            it.weights = sampler.doLookup(gltf, rtime, it.nweights).toVector4()
                        }
                    }
                    GLTF2.Animation.Channel.TargetPath.ROTATION -> {
                        //println("sampler.doLookup(gltf, rtime).toVector4()=${sampler.doLookup(gltf, rtime).toVector4()}")
                        view.rotation(Quaternion(sampler.doLookup(gltf, rtime).toVector4()))
                    }
                    GLTF2.Animation.Channel.TargetPath.TRANSLATION -> {
                        view.position(sampler.doLookup(gltf, rtime).toVector3())
                    }
                    GLTF2.Animation.Channel.TargetPath.SCALE -> {
                        view.scale(sampler.doLookup(gltf, rtime).toVector3())
                    }
                    else -> {
                        println("Unsupported animation target.path=${target.path}")
                    }
                }
            }
        }
    }

    fun skinForNode(node: GLTF2.Node): GLTF2ViewSkin? {
        return if (node.skin >= 0) skinsToView[gltf.skins[node.skin]] else null
    }
}

class GLTF2ViewNode(override val gltf: GLTF2, val node: GLTF2.Node, val view: GLTF2View) : Container3D(), GLTF2Holder {
    val skin: GLTF2ViewSkin? = view.skinForNode(node)
    val meshView = if (node.mesh >= 0) GLTF2ViewMesh(gltf, gltf.meshes[node.mesh], this).addTo(this) else null
    init {
        transform.setMatrix(node.mmatrix.mutable)
        for (child in node.childrenNodes) {
            addChild(GLTF2ViewNode(gltf, child, view))
        }
        view.nodeToViews[node] = this
    }
}

class GLTF2ViewMesh(val gltf: GLTF2, val mesh: GLTF2.Mesh, val viewNode: GLTF2ViewNode) : Container3D() {
    val primitiveViews = mesh.primitives.map {
        GLTF2ViewPrimitive(gltf, it, mesh, this).addTo(this)
    }
}

class GLTF2ViewPrimitive(override val gltf: GLTF2, val primitive: GLTF2.Primitive, val mesh: GLTF2.Mesh, val viewMesh: GLTF2ViewMesh) : ViewWithMaterial3D(), GLTF2Holder {
    val nweights get() = primitive.targets.size
    var weights = mesh.weightsVector
    val drawType: AGDrawType get() = primitive.drawType

    fun genAGVertexData(prim: GLTF2.PrimitiveAttribute, index: Int, targetIndex: Int): AGVertexData {
        val accessor = gltf.accessors[index]
        val bufferView = gltf.bufferViews[accessor.bufferView]
        val buffer = bufferView.slice(gltf).slice(accessor.byteOffset)
        val att = when {
            prim.isPosition -> Shaders3D.a_pos
            prim.isNormal -> Shaders3D.a_nor
            prim.isTangent -> if (accessor.ncomponent == 3) Shaders3D.a_tan3 else Shaders3D.a_tan
            prim.isTexcoord0 -> Shaders3D.a_tex
            prim.isJoints0 -> Shaders3D.a_joints[0]
            prim.isWeights0 -> Shaders3D.a_weights[0]
            else -> TODO("${prim}")
        }
        val expectedComponents = when (att) {
            Shaders3D.a_pos, Shaders3D.a_nor -> 3
            Shaders3D.a_tan3 -> 3
            Shaders3D.a_tan -> 4
            Shaders3D.a_tex -> 2
            Shaders3D.a_joints[0] -> 4
            Shaders3D.a_weights[0] -> 4
            else -> TODO("Unsupported $att")
        }
        check(accessor.ncomponent == expectedComponents) { "$prim in $accessor expected to have $expectedComponents components but had ${accessor.ncomponent}" }

        //when (att) {
        //    Shaders3D.a_pos, Shaders3D.a_nor -> check(accessor.componentTType == VarKind.TFLOAT)
        //    Shaders3D.a_tan, Shaders3D.a_tan3 -> check(accessor.componentTType == VarKind.TFLOAT)
        //    Shaders3D.a_tex -> check(accessor.componentTType == VarKind.TFLOAT)
        //    Shaders3D.a_joints[0] -> check(accessor.componentTType == VarKind.TFLOAT)
        //    Shaders3D.a_weights[0] -> check(accessor.componentTType == VarKind.TFLOAT)
        //    else -> TODO("Unsupported $att")
        //}

        val ratt = when (att) {
            Shaders3D.a_pos -> if (targetIndex < 0) Shaders3D.a_pos else Shaders3D.a_posTarget[targetIndex]
            Shaders3D.a_tan -> if (targetIndex < 0) Shaders3D.a_tan else Shaders3D.a_tanTarget[targetIndex]
            Shaders3D.a_nor -> if (targetIndex < 0) Shaders3D.a_nor else Shaders3D.a_norTarget[targetIndex]
            else -> att
        }

        return AGVertexData(VertexLayout(ratt.copy(
            type = accessor.varType,
            normalized = accessor.requireNormalization
        )), buffer = AGBuffer().also { it.upload(buffer) })
    }

    val njoins = primitive.attributes.count { it.key.isJoints0 } * 4

    val vertexData: AGVertexArrayObject = AGVertexArrayObject(
        *primitive.attributes.map { (prim, index) -> genAGVertexData(prim, index, -1) }.toTypedArray(),
        *primitive.targets.flatMapIndexed { targetIndex, map ->
            map.map { (prim, index) -> genAGVertexData(prim, index, targetIndex) }
        }.toTypedArray(),
    )

    // @TODO:
    val indexAccessor: GLTF2.Accessor = gltf.accessors[primitive.indices]
    val indexType: AGIndexType = indexAccessor.asIndexType()
    val indexDataOffset = 0
    val indexSlice = indexAccessor.bufferSlice(gltf)
    val indexData = AGBuffer().also { it.upload(indexSlice) }
    val vertexCount = indexSlice.sizeInBytes / indexType.bytesSize

    //val meshMaterial = Material3D(diffuse = Material3D.LightTexture(crateTex))
    //override val material = Material3D(diffuse = Material3D.LightColor(Colors.RED.withAd(0.5)))
    override val material = gltf.materials3D[primitive.material]

    override fun putUniforms(ctx: RenderContext3D) {
        super.putUniforms(ctx)
        ctx.rctx[Shaders3D.WeightsUB].push {
            it[u_Weights] = this@GLTF2ViewPrimitive.weights
        }
        // @TODO: We could probably do this once per mesh/node?
        //println("viewMesh.viewNode.skin=${viewMesh.viewNode.skin}")
        viewMesh.viewNode.skin?.let { viewSkin ->
            viewSkin.putUniforms(ctx)
        }
    }

    override fun render(ctx: RenderContext3D) {
        //ctx[DefaultShaders.ProjViewUB].push {
        //    val width = 100f
        //    val height = 100f
        //    it[this.u_ProjMat] = Matrix4.perspective(45.degrees, width / height, 1f, 1000f)
        //    it[this.u_ViewMat] = Matrix4()
        //}

        //val program = ctx.shaders.getProgram3D(
        //val program = PROGRAM ?: ctx.shaders.getProgram3D(
        val program = ctx.shaders.getProgram3D(
            ctx.lights.size.clamp(0, 4),
            nweights,
            material,
            material.hasTexture,
            njoins
            //mesh.hasTexture
        )
        putUniforms(ctx)

        //println("uniformBlocks=$uniformBlocks")

        //println("drawType=$drawType")
        //println("vertexData=$vertexData")

        ctx.ag.draw(
            AGBatch(
                frameBuffer = ctx.rctx.currentFrameBuffer.base,
                frameBufferInfo = ctx.rctx.currentFrameBuffer.info,
                drawType = drawType,
                indexType = indexType,
                indices = indexData,
                vertexData = vertexData,
                cullFace = if (material.doubleSided) AGCullFace.NONE else AGCullFace.BACK,
                program = program,
                uniformBlocks = ctx.rctx.createCurrentUniformsRef(program),
                textureUnits = textureUnits,
                vertexCount = vertexCount,
                drawOffset = 0,
                depthAndFrontFace = DEFAULT_DEPTH_FUNC,
            )
        )
    }

    companion object {
    }
}

//fun createMeshPrimitive(primitive: GLTF2.GPrimitive) {
//    AGVertexArrayObject(AGVertexData())
//    primitive.attributes.map {
//        when (it.key) {
//            GLTF2.GAttribute.POSITION -> Shaders3D.a_pos
//            else -> TODO("${it.key}")
//        }
//    }
//}

