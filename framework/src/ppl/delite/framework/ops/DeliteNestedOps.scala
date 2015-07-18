package ppl.delite.framework.ops

import scala.reflect.SourceContext
import scala.virtualization.lms.common._

import ppl.delite.framework.datastructures._

trait DeliteNestedOps extends RangeVectorOps with Base {
  def nested_forIndices(lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[Unit])(implicit ctx: SourceContext): Rep[Unit]
  def nested_reduce[A:Manifest](zero: Rep[A], lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[A], rF: (Rep[A], Rep[A]) => Rep[A])(implicit ctx: SourceContext): Rep[A]
  def nested_mreduce[A:Manifest](init: => Rep[A], lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[A], rF: (Rep[A], Rep[A]) => Rep[A])(implicit ctx: SourceContext): Rep[A] 
  def nested_collect[A:Manifest,C<:DeliteCollection[A]:Manifest](lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[A])(implicit ctx: SourceContext): Rep[C]
  def tile_assemble[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](dims: List[Rep[Int]], init: => Rep[C], keys: List[ List[Rep[RangeVector]] => Rep[RangeVector] ], tile: List[Rep[RangeVector]] => Rep[T], reduce: Option[(Rep[T],Rep[T]) => Rep[T] ], mutable: Boolean = false)(implicit ctx: SourceContext): Rep[C]
}

trait DeliteNestedOpsExp extends DeliteNestedOps with DeliteOpsExpIR with DeliteCollectionOpsExp with DeliteArrayOpsExp with RangeVectorOpsExp { 
  this: DeliteOpsExp => 

  /**
   * Nested parallel, effectful foreach
   * @param sizes - iteration dimensions
   * @param func  - the foreach function
   */
  case class NestedForeach(oVs: List[Sym[Int]], lSizes: List[Exp[Int]], func: Block[Unit])(implicit ctx: SourceContext) extends DeliteOpLoopNest[Unit] {
    type OpType <: NestedForeach

    val nestLayers = lSizes.length  // Input domain rank
    lazy val vs: List[Sym[Int]] = copyOrElse(_.vs)(oVs)
    val sizes: List[Exp[Int]] = copyTransformedSymListOrElse(_.sizes)(lSizes)
    val strides: List[Exp[Int]] = List.fill(nestLayers)(unit(1))

    lazy val body: Def[Unit] = copyBodyOrElse(DeliteForeachElem(
      func = this.func,
      numDynamicChunks = this.numDynamicChunks
    ))
  }
  object ForeachHelper {
    def mirror(op: NestedForeach, f: Transformer)(implicit ctx: SourceContext) = op match {
      case NestedForeach(v,s,b) => new { override val original = Some(f,op) } with NestedForeach(v,s,b)(ctx)
    }
  }

  def nested_forIndices(lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[Unit])(implicit ctx: SourceContext): Rep[Unit] = {
    val vs = List.fill(lSizes.length)(fresh[Int].asInstanceOf[Sym[Int]])
    val func = reifyEffects(f(vs))
    reflectEffect(NestedForeach(vs, lSizes, func), summarizeEffects(func).star andAlso Simple())
  }

  /**
   * Nested parallel reduction. Reducing function must be associative.
   * TODO: This will need to be changed with Vera's fold/reduce distinction
   * @param oVs      - symbols for loop iterators
   * @param orV      - pair of symbols representing two elements (Sym[A], Sym[A])
   * @param lSizes   - iteration dimensions
   * @param func     - map function to obtain elements to reduce
   * @param rFunc    - the reduction function; (Block[A]) Must be associative.
   * @param init     - accumulator initialization - used only if mutable is true
   * @param zero     - zero value (not actually used to compute output result)
   * @param filter   - optional filter function
   * @param mutable  - mutable reduce (reduction is done by directly updating the accumulator)
   */
  case class NestedReduce[A:Manifest](oVs: List[Sym[Int]], orV: (Sym[A],Sym[A]), lSizes: List[Exp[Int]], func: Block[A], rFunc: Block[A], init: Block[A], zero: Block[A], fFunc: List[Block[Boolean]], mutable: Boolean)(implicit ctx: SourceContext) extends DeliteOpLoopNest[A] {
    type OpType <: NestedReduce[A]
    
    val nestLayers = lSizes.length  // Input domain rank
    lazy val vs: List[Sym[Int]] = copyOrElse(_.vs)(oVs)
    lazy val rV: (Sym[A],Sym[A]) = copyOrElse(_.rV)(orV)
    lazy val sizes: List[Exp[Int]] = copyTransformedSymListOrElse(_.sizes)(lSizes)
    lazy val strides: List[Exp[Int]] = List.fill(nestLayers)(unit(1))

    // If stripFirst is false, accInit is used to allocate the accumulator, and is used in the reduction
    // Zero is only used if the collection is empty or if the first iteration of the filter function is false
    lazy val body: Def[A] = copyBodyOrElse(DeliteReduceElem[A](
      func = this.func,
      cond = this.fFunc,
      zero = this.zero,
      accInit = this.init,
      rV = this.rV,
      rFunc = this.rFunc,
      stripFirst = !mutable, 
      numDynamicChunks = this.numDynamicChunks
    ))
    val mA = manifest[A]
  }
  object ReduceHelper {
    def mirror[A:Manifest](op: NestedReduce[A], f: Transformer)(implicit ctx: SourceContext): NestedReduce[A] = op match {
      case NestedReduce(v,r,s,l,b,i,z,c,m) => new { override val original = Some(f,op) } with NestedReduce(v,r,s,l,b,i,z,c,m)(op.mA, ctx)
    }
    def unerase[A:Manifest](op: NestedReduce[_]): NestedReduce[A] = op.asInstanceOf[NestedReduce[A]]
  }

  def nested_reduce[A:Manifest](zero: Rep[A], lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[A], rF: (Rep[A], Rep[A]) => Rep[A])(implicit ctx: SourceContext): Rep[A] = {
    val vs: List[Sym[Int]] = List.fill(lSizes.length)(fresh[Int].asInstanceOf[Sym[Int]])
    val zeroB: Block[A] = reifyEffects(zero)
    val rV = (fresh[A], fresh[A])
    val func = reifyEffects(f(vs))
    val rFunc = reifyEffects(rF(rV._1,rV._2))
    reflectPure( NestedReduce(vs, rV, lSizes, func, rFunc, zeroB, zeroB, Nil, false))
  }
  def nested_mreduce[A:Manifest](init: => Rep[A], lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[A], rF: (Rep[A], Rep[A]) => Rep[A])(implicit ctx: SourceContext): Rep[A] = {
    val vs: List[Sym[Int]] = List.fill(lSizes.length)(fresh[Int].asInstanceOf[Sym[Int]])
    val initB: Block[A] = reifyEffects(init)
    val rVa = reflectMutableSym(fresh[A])
    val rVb = fresh[A]
    val func = reifyEffects(f(vs))
    val rFunc = reifyEffects(rF(rVa,rVb))
    reflectPure( NestedReduce(vs, (rVa,rVb), lSizes, func, rFunc, initB, initB, Nil, true) )
  }
 

  /**
   * Nested parallel collect (includes map, zipWith, mapIndices)
   * @param oVs       - symbols for loop iterators
   * @param lSizes    - size of loop (usually size of input collection)
   * @param func      - map/collect function  (anything that productes Exp[R])
   */
  case class NestedCollect[A:Manifest,C<:DeliteCollection[A]:Manifest](oVs: List[Sym[Int]], lSizes: List[Exp[Int]], func: Block[A])(implicit ctx: SourceContext) extends DeliteOpLoopNest[C] {
    type OpType <: NestedCollect[A,C]

    val nestLayers = lSizes.length  // Input domain rank
    lazy val vs: List[Sym[Int]] = copyOrElse(_.vs)(oVs)
    lazy val sizes: List[Exp[Int]] = copyTransformedSymListOrElse(_.sizes)(lSizes)
    lazy val strides: List[Exp[Int]] = List.fill(nestLayers)(unit(1))

    lazy val eV: Sym[A] = copyTransformedOrElse(_.eV)(fresh[A]).asInstanceOf[Sym[A]]
    lazy val sV: Sym[Int] = copyTransformedOrElse(_.sV)(fresh[Int]).asInstanceOf[Sym[Int]]
    lazy val iV: Sym[Int] = copyTransformedOrElse(_.iV)(fresh[Int]).asInstanceOf[Sym[Int]]
    lazy val iV2: Sym[Int] = copyTransformedOrElse(_.iV2)(fresh[Int]).asInstanceOf[Sym[Int]]
    lazy val aV2: Sym[C] = copyTransformedOrElse(_.aV2)(fresh[C]).asInstanceOf[Sym[C]]
    lazy val allocVal: Sym[C] = copyTransformedOrElse(_.allocVal)(reflectMutableSym(fresh[C])).asInstanceOf[Sym[C]]

    lazy val buf = DeliteBufferElem[A,C,C](
      eV = this.eV,     // Current map result
      sV = this.sV,     // intermediate size? (should be unused)
      iV = this.iV,     // copy destination start? (should be unused)
      iV2 = this.iV2,   // copy source start?
      allocVal = this.allocVal,
      aV2 = this.aV2,
      alloc = reifyEffects(dc_alloc_block[A,C](this.allocVal, this.lSizes, Nil)),
      apply = unusedBlock,
      update = reifyEffects(dc_block_update(this.allocVal,vs,this.eV, Nil)),
      append = unusedBlock, //reifyEffects(dc_append(this.allocVal,v,this.eV)),
      appendable = unusedBlock, //reifyEffects(dc_appendable(this.allocVal,v,this.eV)),
      setSize = unusedBlock, //reifyEffects(dc_set_logical_size(this.allocVal,this.sV)),
      allocRaw = unusedBlock, //reifyEffects(dc_alloc[R,C](this.allocVal,this.sV)),
      copyRaw = unusedBlock, //reifyEffects(dc_copy(this.aV2,this.iV,this.allocVal,this.iV2,this.sV)),
      finalizer = unusedBlock
    )
    lazy val body: Def[C] = copyBodyOrElse(DeliteCollectElem[A,C,C](
      func = this.func,
      cond = Nil,
      par = ParFlat, //dc_parallelization(allocVal, hasConditions = false),
      buf = this.buf,
      numDynamicChunks = this.numDynamicChunks
    ))

    val mA = manifest[A]
    val mC = manifest[C]
  }

  object CollectHelper {
    def mirror[A:Manifest,C<:DeliteCollection[A]:Manifest](op: NestedCollect[A,C], f: Transformer)(implicit ctx: SourceContext): NestedCollect[A,C] = op match {
      case NestedCollect(v,s,b) => new { override val original = Some(f,op) } with NestedCollect(v,s,b)(op.mA,op.mC,ctx)
    }
    def unerase[A:Manifest,C<:DeliteCollection[A]:Manifest](op: NestedCollect[_,_]): NestedCollect[A,C] = op.asInstanceOf[NestedCollect[A,C]]
  }

  def nested_collect[A:Manifest,C<:DeliteCollection[A]:Manifest](lSizes: List[Rep[Int]], f: List[Rep[Int]] => Rep[A])(implicit ctx: SourceContext): Rep[C] = {
    val vs: List[Sym[Int]] = List.fill(lSizes.length)(fresh[Int].asInstanceOf[Sym[Int]])
    val func = reifyEffects(f(vs))
    reflectPure( NestedCollect[A,C](vs, lSizes, func) )
  }


  /**
   * Tile Assemble (tiling parallel ops)
   * Currently roughly equivalent to a GroupByReduce-FlatMap
   *
   * TODO: Can we use a slice/view rather than an explicit copy?
   * FIXME: Using a hack right now to make tiled scalar reduction work
   *
   * @param oVs       - symbols for nested loop iterators
   * @param orV       - symbols used to reify reduction function (unused if reduction function is None)
   * @param lSizes    - sizes of nested loops
   * @param init      - buffer allocation function
   * @param lStrides  - list of input domain blocking factors
   * @param kFunc     - list of key functions - # of keys should equal rank of output
   * @param fFunc     - list of filter functions
   * @param func      - main body function (produces a single tile)
   * @param rFunc     - optional reduction function for partial updates
   * @param tDims     - tile dimensions (maximum size)
   * @param unitDims  - dimensions "dropped" between output collection and tile
   */
  case class TileAssemble[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](oVs: List[Sym[Int]], orV: (Sym[T],Sym[T]), lSizes: List[Exp[Int]], init: Block[C], lStrides: List[Exp[Int]], kFunc: List[Block[RangeVector]], fFunc: List[Block[Boolean]], func: Block[T], rFunc: Option[Block[T]], tDims: List[Exp[Int]], unitDims: List[Int])(implicit ctx: SourceContext) extends DeliteOpLoopNest[C] {
    type OpType <: TileAssemble[A,T,C]

    val nestLayers = lSizes.length  // Input domain rank
    lazy val vs: List[Sym[Int]] = copyOrElse(_.vs)(oVs)
    lazy val rV: (Sym[T],Sym[T]) = copyOrElse(_.rV)(orV)

    val sizes: List[Exp[Int]] = copyTransformedSymListOrElse(_.sizes)(lSizes)
    val strides: List[Exp[Int]] = copyTransformedSymListOrElse(_.strides)(lStrides)

    val n = kFunc.length            // Output rank
    
    // --- bound vars
    final lazy val bS: List[Sym[RangeVector]] = copyOrElse(_.bS)(List.fill(n){fresh[RangeVector].asInstanceOf[Sym[RangeVector]]})
    final lazy val bV: List[Sym[Int]] = copyOrElse(_.bV)(List.fill(n){fresh[Int].asInstanceOf[Sym[Int]]})
    final lazy val tV: List[Sym[Int]] = copyOrElse(_.tV)(List.fill(n){fresh[Int].asInstanceOf[Sym[Int]]})
    final lazy val tD: List[Sym[Int]] = copyOrElse(_.tD)(List.fill(n){fresh[Int].asInstanceOf[Sym[Int]]})
    final lazy val buffVal: Sym[C] = copyTransformedOrElse(_.buffVal)(reflectMutableSym(fresh[C])).asInstanceOf[Sym[C]]
    final lazy val tileVal: Sym[T] = copyTransformedOrElse(_.tileVal)(fresh[T]).asInstanceOf[Sym[T]]
    final lazy val partVal: Sym[T] = copyTransformedOrElse(_.partVal)(reflectMutableSym(fresh[T])).asInstanceOf[Sym[T]]
    final lazy val bE: Sym[A] = copyTransformedOrElse(_.bE)(fresh[A]).asInstanceOf[Sym[A]]
    final lazy val tE: Sym[A] = copyTransformedOrElse(_.tE)(fresh[A]).asInstanceOf[Sym[A]]

    lazy val body: Def[C] = copyBodyOrElse(DeliteTileElem[A,T,C](
      keys = this.kFunc,
      cond = this.fFunc,
      tile = this.func,
      rV = this.rV,
      rFunc = this.rFunc,
      buf = DeliteTileBuffer[A,T,C](
        bS = this.bS,
        bV = this.bV,
        tV = this.tV,
        tD = this.tD,
        buffVal = this.buffVal,
        tileVal = this.tileVal,
        partVal = this.partVal,
        bE = this.bE,
        tE = this.tE,

        bApply = reifyEffects(dc_block_apply(buffVal, bV, Nil)),
        tApply = reifyEffects(dc_block_apply(tileVal, tV, unitDims)),
        bUpdate = reifyEffects(dc_block_update(buffVal, bV, tE, Nil)),
        tUpdate = reifyEffects(dc_block_update(partVal, tV, bE, unitDims)),
        allocBuff = init,
        allocTile = reifyEffects(dc_alloc_block[A,T](partVal, this.tDims, unitDims))
      ),
      numDynamicChunks = this.numDynamicChunks
    ))
    val mA = manifest[A]
    val mT = manifest[T]
    val mC = manifest[C]
  }

  object TileAssembleHelper {
    def mirror[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](op: TileAssemble[A,T,C], f: Transformer)(implicit ctx: SourceContext): TileAssemble[A,T,C] = op match {
      case TileAssemble(v,rv,ls,in,st,kF,fF,g,rF,tD,uD) => 
        new {override val original = Some(f,op)} with TileAssemble[A,T,C](v,rv,ls,in,st,kF,fF,g,rF,tD,uD)(op.mA,op.mT,op.mC,ctx)
    }
    def unerase[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](op: TileAssemble[_,_,_]): TileAssemble[A,T,C] = op.asInstanceOf[TileAssemble[A,T,C]]
  }

  def tile_assemble[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](dims: List[Exp[Int]], init: => Rep[C], keys: List[ List[Exp[RangeVector]] => Exp[RangeVector] ], tile: List[Exp[RangeVector]] => Exp[T], reduce: Option[(Exp[T],Exp[T]) => Exp[T] ], mutable: Boolean = false)(implicit ctx: SourceContext): Exp[C] = {
    val n = dims.length

    val strides: List[Tunable] = List.tabulate(n){i => freshTuna(dims(i)) }
    val vs: List[Sym[Int]] = List.fill(n)(fresh[Int])
    val rV = if (!mutable) (fresh[T],fresh[T])  else (reflectMutableSym(fresh[T]), fresh[T])

    // Input domain RangeVectors
    def iSize(i: Int): Exp[Int] = strides(i)  // Math.min(dims(i) - vs(i), strides(i))
    val rvs: List[Exp[RangeVector]] = List.tabulate(n){i => RangeVector(vs(i), iSize(i)) }

    // Tile/Output RangeVector functions
    val kFunc: List[Block[RangeVector]] = keys.map{key => reifyEffects(key(rvs)) }
    val func: Block[T] = reifyEffects(tile(rvs))

    // HACK: Find tile dimensions by stripping off first iteration of the kFunc for use as tile sizes
    // Contract is that the either all tiles are equally sized or the first iteration has the largest tile size
    def firstIterSize(i: Int): Exp[Int] = strides(i) // Math.min(dims(i), strides(i))
    val rvsIter1 = List.tabulate(n){i => RangeVector(unit(0), firstIterSize(i)) }

    val tDims: List[Exp[Int]] = keys.map{key => key(rvsIter1).length }

    // HACK: find unit dimensions by checking the given lengths of each RangeVector key result..
    val constDims: List[Int] = tDims.zipWithIndex.filter{k => k._1 match {case Const(1) => true; case _ => false }}.map{_._2}

    // HACK: Scalar reduction is done right now using a 1D array of size 1 - can't ignore all dims in this case
    val unitDims = if (constDims.length == tDims.length) constDims.drop(1) else constDims

    val buffAlloc: Block[C] = reifyEffects(init)
    val rFunc = reduce.map{rF => reifyEffects(rF(rV._1,rV._2))}

    reflectPure( TileAssemble[A,T,C](vs, rV, dims, buffAlloc, strides, kFunc, Nil, func, rFunc, tDims, unitDims) )
  }

  /**
   * Block Slice
   * Create an m-dimensional slice from an n-dimensional collection
   * TODO: What should this be? An elem? A single task? A loop with a buffer body? Definitely can't fuse with anything right now
   * TODO: Probably need to move this node elsewhere 
   *
   * destDims should have n elements, some of which may be Const(1).
   * indices of elements of destDims which are Const(1) should be in unitDims
   */
  case class BlockSlice[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](src: Exp[C], srcOffsets: List[Exp[Int]], srcStrides: List[Exp[Int]], destDims: List[Exp[Int]], unitDims: List[Int])(implicit ctx: SourceContext) extends DeliteOp[T] {
    type OpType <: BlockSlice[A,T,C]

    val n = srcOffsets.length
    val m = destDims.length - unitDims.length
    val deltaInds = List.tabulate(n){i=>i}.filterNot{i => unitDims.contains(i) }

    val nestLayers = m
    val sizes: List[Exp[Int]] = copyTransformedSymListOrElse(_.sizes)(destDims)     // dest dimensions
    val strides: List[Exp[Int]] = copyTransformedSymListOrElse(_.strides)(srcStrides) // src strides (for non-unit dims)

    // Bound variables
    val vs: List[Sym[Int]] = copyOrElse(_.vs)(List.fill(m)(fresh[Int].asInstanceOf[Sym[Int]]))    // dest indices
    val bV: List[Sym[Int]] = copyOrElse(_.bV)( List.fill(n)(fresh[Int].asInstanceOf[Sym[Int]]))  // src indices
    val tileVal: Sym[T] = copyTransformedOrElse(_.tileVal)(reflectMutableSym(fresh[T])).asInstanceOf[Sym[T]]      // dest buffer
    val bE: Sym[A] = copyTransformedOrElse(_.bE)(fresh[A]).asInstanceOf[Sym[A]]          // Single element (during copying out)

    // collection functions
    val bApply: Block[A] = copyTransformedBlockOrElse(_.bApply)(reifyEffects(dc_block_apply(src, bV, Nil)))           // src apply
    val tUpdate: Block[Unit] = copyTransformedBlockOrElse(_.tUpdate)(reifyEffects(dc_block_update(tileVal, vs, bE, Nil)))  // dest update
    val allocTile: Block[T] = copyTransformedBlockOrElse(_.allocTile)(reifyEffects(dc_alloc_block[A,T](tileVal, destDims, Nil))) // dest alloc 

    val mA = manifest[A]
    val mT = manifest[T]
    val mC = manifest[C]
  }

  object BlockSliceHelper {
    def mirror[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](op: BlockSlice[A,T,C], f: Transformer)(implicit ctx: SourceContext): BlockSlice[A,T,C] = op match {
      case BlockSlice(src,srcO,srcS,dD,uD) => 
        new {override val original = Some(f,op)} with BlockSlice[A,T,C](f(src),f(srcO),f(srcS),f(dD),uD)(op.mA,op.mT,op.mC,ctx)
    }
    def unerase[A:Manifest,T<:DeliteCollection[A]:Manifest,C<:DeliteCollection[A]:Manifest](op: BlockSlice[_,_,_]): BlockSlice[A,T,C] = op.asInstanceOf[BlockSlice[A,T,C]]
  }

  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = (e match {
    case e: NestedForeach => 
      reflectPure(ForeachHelper.mirror(e,f)(pos))(mtype(manifest[A]), pos)
    case Reflect(e: NestedForeach, u, es) => 
      reflectMirrored(Reflect(ForeachHelper.mirror(e,f)(pos), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case e: NestedReduce[_] => 
      val op = ReduceHelper.unerase(e)(e.mA)
      reflectPure( ReduceHelper.mirror(op,f)(e.mA,pos))(mtype(manifest[A]), pos)
    case Reflect(e: NestedReduce[_], u, es) => 
      val op = ReduceHelper.unerase(e)(e.mA)
      reflectMirrored(Reflect(ReduceHelper.mirror(op,f)(e.mA,pos), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case e: NestedCollect[_,_] => 
      val op = CollectHelper.unerase(e)(e.mA,e.mC)
      reflectPure(CollectHelper.mirror(op,f)(e.mA,e.mC,pos))(mtype(manifest[A]), pos)
    case Reflect(e: NestedCollect[_,_], u, es) => 
      val op = CollectHelper.unerase(e)(e.mA,e.mC)
      reflectMirrored(Reflect(CollectHelper.mirror(op,f)(e.mA,e.mC,pos), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case e: TileAssemble[_,_,_] => 
      val op = TileAssembleHelper.unerase(e)(e.mA,e.mT,e.mC)
      reflectPure(TileAssembleHelper.mirror(op,f)(e.mA,e.mT,e.mC,pos))(mtype(manifest[A]), pos)
    case Reflect(e: TileAssemble[_,_,_], u, es) =>
      val op = TileAssembleHelper.unerase(e)(e.mA,e.mT,e.mC)
      reflectMirrored(Reflect(TileAssembleHelper.mirror(op,f)(e.mA,e.mT,e.mC,pos), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case e: BlockSlice[_,_,_] => 
      val op = BlockSliceHelper.unerase(e)(e.mA,e.mT,e.mC)
      reflectPure(BlockSliceHelper.mirror(op,f)(e.mA,e.mT,e.mC,pos))(mtype(manifest[A]), pos)
    case Reflect(e: BlockSlice[_,_,_], u, es) =>
      val op = BlockSliceHelper.unerase(e)(e.mA,e.mT,e.mC)
      reflectMirrored(Reflect(BlockSliceHelper.mirror(op,f)(e.mA,e.mT,e.mC,pos), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case _ => super.mirror(e,f)
  }).asInstanceOf[Exp[A]]

  override def blocks(e: Any): List[Block[Any]] = e match {
    case op: NestedForeach => Nil
    case op: NestedReduce[_] => Nil
    case op: NestedCollect[_,_] => Nil
    case op: TileAssemble[_,_,_] => Nil
    case op: BlockSlice[_,_,_] => Nil
  }

  // dependencies
  override def syms(e: Any): List[Sym[Any]] = e match {
    case op: BlockSlice[_,_,_] => syms(op.src) ::: syms(op.srcOffsets) ::: syms(op.strides) ::: syms(op.sizes) ::: syms(op.bApply) ::: syms(op.tUpdate) ::: syms(op.allocTile)
    case _ => super.syms(e)
  }

  override def readSyms(e: Any): List[Sym[Any]] = e match {
    case op: BlockSlice[_,_,_] => readSyms(op.src) ::: readSyms(op.srcOffsets) ::: readSyms(op.strides) ::: readSyms(op.sizes) ::: readSyms(op.bApply) ::: readSyms(op.tUpdate) ::: readSyms(op.allocTile)
    case _ => super.readSyms(e)
  }

  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case op: BlockSlice[_,_,_] => op.vs ::: op.bV ::: List(op.tileVal, op.bE) ::: effectSyms(op.bApply) ::: effectSyms(op.tUpdate) ::: effectSyms(op.allocTile) 
    case _ => super.boundSyms(e)
  }

  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case op: BlockSlice[_,_,_] => freqNormal(op.src) ::: freqNormal(op.srcOffsets) ::: freqNormal(op.strides) ::: freqNormal(op.sizes) ::: freqHot(op.bApply) ::: freqHot(op.tUpdate) ::: freqNormal(op.allocTile)
    case _ => super.symsFreq(e)
  }

  // aliases and sharing
  override def aliasSyms(e: Any): List[Sym[Any]] = e match {
    case op: BlockSlice[_,_,_] => Nil
    case _ => super.aliasSyms(e)
  }
  override def containSyms(e: Any): List[Sym[Any]] = e match {
    case op: BlockSlice[_,_,_] => Nil
    case _ => super.containSyms(e)
  }
  override def extractSyms(e: Any): List[Sym[Any]] = e match {
    case op: BlockSlice[_,_,_] => Nil
    case _ => super.extractSyms(e)
  }
  override def copySyms(e: Any): List[Sym[Any]] = e match {
    case op: BlockSlice[_,_,_] => Nil
    case _ => super.copySyms(e)
  }
}

// --- Generation of Nested ops
// TODO: Only sequential right now
// TODO: Probably want to move this elsewhere
trait ScalaGenNestedOps extends ScalaGenDeliteOps {
  val IR: DeliteNestedOpsExp with DeliteOpsExp
  import IR._

  // HACK: Allows non-vars to be generated as vars...
  private def emitBoundVarDef(sym: Sym[Any], rhs: String): Unit = {
    stream.println("var " + quote(sym) + ": " + remap(sym.tp) + " = " + rhs)
  }

  // --- Foreach
  private def emitForeachElem(sym: Sym[Any], elem: DeliteForeachElem[_]) {
    emitBlock(elem.func)
  }

  // --- Reduce
  private def emitReduceElemStripped(sym: Sym[Any], elem: DeliteReduceElem[_]) {

    if (elem.cond.nonEmpty) {
      elem.cond.foreach( emitBlock(_) )
      stream.println("if (" + elem.cond.map(c=>quote(getBlockResult(c))).mkString(" && ") + ") {"/*}*/)
      emitBlock(elem.func)
      emitAssignment(sym, quote(getBlockResult(elem.func)))
      stream.println("} else {")
      emitAssignment(sym, quote(sym)+"_zero")
      stream.println("}")
    }
    else {
      emitBlock(elem.func)
      emitAssignment(sym, quote(getBlockResult(elem.func)))
    }
  }

  private def emitReduceElem(sym: Sym[Any], elem: DeliteReduceElem[_]) {
    stream.println("// ---- Begin Reduce Elem")
    if (elem.cond.nonEmpty) {
      elem.cond.foreach( emitBlock(_) )
      stream.println("if (" + elem.cond.map(c=>quote(getBlockResult(c))).mkString(" && ") + ") {"/*}*/)
      emitBlock(elem.func)
      if (elem.stripFirst) 
        emitInitOrReduction(sym, elem)
      else
        emitReduction(sym, elem)
      stream.println("}")
    }
    else {
      emitBlock(elem.func)
      emitReduction(sym, elem)
    }   
    stream.println("// --- End Reduce Elem")
  }

  private def emitInitOrReduction(sym: Sym[Any], elem: DeliteReduceElem[_]) {
    stream.println("// TODO: we could optimize this check away with more convoluted runtime support if necessary")
    stream.println("if (" + quote(sym) + " == " + quote(sym)+"_zero" + ")")
    // initialize
    emitAssignment(sym, quote(getBlockResult(elem.func)))
    // or reduce
    stream.println("else {")
    emitReduction(sym, elem)
    stream.println("}")
  }

  private def emitReduction(sym: Sym[Any], elem: DeliteReduceElem[_]) {
    emitValDef(elem.rV._1, quote(sym))
    emitValDef(elem.rV._2, quote(getBlockResult(elem.func)))
    emitBlock(elem.rFunc)
    emitAssignment(sym, quote(getBlockResult(elem.rFunc)))
  }

  // --- Collect
  private def emitCollectElem(sym: Sym[Any], elem: DeliteCollectElem[_,_,_]) {
    emitBlock(elem.func)
    emitValDef(elem.buf.eV, quote(getBlockResult(elem.func)))
    emitBlock(elem.buf.update)
  }

  private def emitPrealloc(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case elem: DeliteTileElem[_,_,_] => 
      stream.println("// --- Alloc output buffer")
      emitBlock(elem.buf.allocBuff)
      emitValDef(elem.buf.buffVal, quote(getBlockResult(elem.buf.allocBuff)))

      if (elem.rFunc.nonEmpty) {
        stream.println("// --- Alloc partial result tile")
        emitBlock(elem.buf.allocTile)
        emitValDef(elem.buf.partVal, quote(getBlockResult(elem.buf.allocTile)))
      }
    case elem: DeliteCollectElem[_,_,_] =>
      stream.println("// --- Preallocate collect result")
      emitBlock(elem.buf.alloc)
      emitValDef(quote(elem.buf.allocVal), remap(getBlockResult(elem.buf.alloc).tp), quote(getBlockResult(elem.buf.alloc)))

    case elem: DeliteReduceElem[_] => 
      stream.println("// --- Create reduce zero")
      emitBlock(elem.zero)
      emitValDef(quote(sym) + "_zero", remap(sym.tp), quote(getBlockResult(elem.zero)))
      emitVarDef(quote(sym), remap(sym.tp), quote(sym) + "_zero")

    case _ => // Nothing
  }

  // TODO: This will need to be changed for fused nested loops
  private def emitStripFirst(sym: Sym[Any], op: AbstractLoopNest[_]): Boolean = if (loopBodyNeedsStripFirst(op.body)) {
    val dimsCheck = op.sizes.map{ quote(_) + " > 0"}.mkString(" && ")
    stream.println("if (" + dimsCheck + ") { // prerun loop " + quote(sym) )
    // Strip off the first iteration indices
    for(i <- 0 until op.nestLayers) {
      emitValDef(op.vs(i), "0")
    }
    op.body match {
      case elem: DeliteReduceElem[_] => emitReduceElemStripped(sym, elem)
    }

    stream.println("}")
    (true)
  } else (false)


  private def emitPostLoop(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case elem: DeliteTileElem[_,_,_] => 
      emitValDef(sym, quote(elem.buf.buffVal))

    case elem: DeliteForeachElem[_] => 
      emitValDef(quote(sym), remap(sym.tp), "()")  //TODO: Need this for other targets? (Currently, other targets just don't generate unit types)

    case elem: DeliteCollectElem[_,_,_] => 
      // Removed unused finalizer call
      emitValDef(sym, quote(elem.buf.allocVal))

    case _ => // Nothing
  }

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case op: BlockSlice[_,_,_] => 
      stream.println("// --- Preallocate output")
      emitBlock(op.allocTile)
      emitValDef(op.tileVal, quote(getBlockResult(op.allocTile)))

      for (i <- 0 until op.unitDims.length) {
        emitValDef(op.bV( op.unitDims(i)), quote(op.srcOffsets(op.unitDims(i))) )
      }

      stream.println("// --- Block slice")
      for (i <- 0 until op.m) {
        emitBoundVarDef(op.bV( op.deltaInds(i)), quote(op.srcOffsets(op.deltaInds(i))) )
        stream.println("for (" + quote(op.vs(i)) + " <- 0 until " + quote(op.destDims(op.deltaInds(i))) + ") {")
      }
      emitBlock(op.bApply)
      emitValDef(op.bE, quote(getBlockResult(op.bApply)))
      emitBlock(op.tUpdate)

      for (i <- 0 until op.m) {
        stream.println(quote(op.bV( op.deltaInds(op.m - i - 1))) + " += " + quote( op.strides( op.deltaInds(op.m - i - 1) ) ) )
        stream.println("}")
      }

      emitValDef(sym, quote(op.tileVal))

    case op: AbstractLoopNest[_] => 
      val vs = op.vs
      val sizes = op.sizes
      val strides = op.strides
      emitPrealloc(sym, op.body)

      val stripFirst = emitStripFirst(sym, op)
      val n = op.nestLayers
      if (stripFirst) 
        emitVarDef(quote(vs(n-1)) + "_start", remap(vs(n-1).tp), quote(strides(n-1)))

      stream.println("// Begin LoopNest")
      for (i <- 0 until n) { 
        val start = if (i < n - 1 || !stripFirst) "0" else quote(vs(n-1)) + "_start"
        stream.println("for (" + quote(vs(i)) + " <- " + start + " until " + quote(sizes(i)) + " by " + quote(strides(i)) + ") {")
      }
      emitNode(sym, op.body)
      if (stripFirst) {
        stream.println("}")
        emitAssignment(quote(vs(n-1)) + "_start", "0")
        stream.println("}"*(n-1) + " // End LoopNest")
      }
      else
        stream.println("}"*n + " // End LoopNest")

      emitPostLoop(sym, op.body)

    case elem: DeliteForeachElem[_] => emitForeachElem(sym, elem)
    case elem: DeliteReduceElem[_] => emitReduceElem(sym, elem)
    case elem: DeliteCollectElem[_,_,_] => emitCollectElem(sym, elem)

    case op: DeliteTileElem[_,_,_] => 
      val nK = op.keys.length

      if (op.cond.nonEmpty) { stream.println("if (true) { //TODO: Filter functions");  }

      stream.println("// --- Block body")
      emitBlock(op.tile)
      emitBoundVarDef(op.buf.tileVal, quote(getBlockResult(op.tile)))

      stream.println("// --- Keys ")
      for (k <- 0 until nK) {
        emitBlock(op.keys(k))
        emitValDef(op.buf.bS(k), quote(getBlockResult(op.keys(k))))
        emitValDef(op.buf.tD(k), quote(op.buf.bS(k)) + ".length")
      }

      if (op.rFunc.isDefined) {
        stream.println("// --- Accumulator copy out")
        for (k <- 0 until nK) {
          emitBoundVarDef(op.buf.bV(k), quote(op.buf.bS(k)) + ".start")
          stream.println("for (" + quote(op.buf.tV(k)) + " <- 0 until " + quote(op.buf.tD(k)) + ") {")
        }
        emitBlock(op.buf.bApply)
        emitValDef(op.buf.bE, quote(getBlockResult(op.buf.bApply)))
        emitBlock(op.buf.tUpdate)

        // TODO: Shouldn't have plus explicitly emitted here?
        // TODO: Stride is assumed to be 1 here
        for (k <- 0 until nK) {
          stream.println(quote(op.buf.bV(nK - k - 1)) + " += 1")
          //emitAssignment(op.buf.bV(k), quote(op.buf.bV(k)) + " + 1")
          stream.println("}")
        }

        stream.println("// --- Reduction")
        emitValDef(op.rV._1, quote(op.buf.partVal))
        emitValDef(op.rV._2, quote(op.buf.tileVal))
        emitBlock(op.rFunc.get)
        emitAssignment(op.buf.tileVal, quote(getBlockResult(op.rFunc.get)))
      }

      stream.println("// --- Accumulator copy in")
      for (k <- 0 until nK) {
        if (op.rFunc.isDefined && k == 0) emitAssignment(op.buf.bV(k), quote(op.buf.bS(k)) + ".start")
        else emitBoundVarDef(op.buf.bV(k), quote(op.buf.bS(k)) + ".start")
        stream.println("for (" + quote(op.buf.tV(k)) + " <- 0 until " + quote(op.buf.tD(k)) + ") {")
      }
      emitBlock(op.buf.tApply)
      emitValDef(op.buf.tE, quote(getBlockResult(op.buf.tApply)))
      emitBlock(op.buf.bUpdate)

      // TODO: Shouldn't have plus explicitly emitted here?
      // TODO: Stride is assumed to be 1 here
      for (k <- 0 until op.keys.length) {
        stream.println(quote(op.buf.bV(nK - k - 1)) + " += 1")
        //emitAssignment(op.buf.bV(k), quote(op.buf.bV(k)) + " + 1")
        stream.println("}")
      }

      if (op.cond.nonEmpty) { stream.println("}") }

    case _ => super.emitNode(sym, rhs)
  }

}


