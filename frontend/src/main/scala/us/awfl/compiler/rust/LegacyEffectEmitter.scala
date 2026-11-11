package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import ExpressionEmitter.{q, render}
import StructuredEmitter.emitStep

/** ABI 2, protocol v1 continuation codegen: retained unchanged for existing artifacts.
  * No further effectful control-flow features should be implemented here.
  * New features belong in the generalized control-flow/state-machine backend.
  */
object LegacyEffectEmitter {
  private def topLevelSlot(step: Instruction): Option[String] = step match {
    case s: Choose => Some(s.resultSlot)
    case s: RepeatRange => Some(s.resultSlot)
    case s: RepeatEach => Some(s.resultSlot)
    case s: ReduceEach => Some(s.resultSlot)
    case s: Effect => Some(s.resultSlot)
    case _ => None
  }

  /** Effectful range bodies use an ordinal frame plus, for multiple Calls,
    * a second frame identifying the effect site. Prior results in the current
    * iteration are checkpointed alongside completed iteration outputs.
    * Nested effects in other control flow still fail closed.
    */
  private def emitEffectRange(loop: RepeatRange, index: Int,
                              prior: Vector[String]): String = {
    val where = s"node=${loop.nodeId} step='${loop.displayName}'"
    val nested = Program("range-effects", loop.body, Vector.empty).effects
    val direct = loop.body.collect { case effect: Effect => effect }
    if (direct.isEmpty || nested.size != direct.size)
      throw CompileError(where, "effectful ForRange requires direct Calls only; effects in nested control flow unsupported")
    val multiSite = direct.size > 1
    val expectedFrames = if (multiSite) 2 else 1
    val siteCases = direct.zipWithIndex.map { (e, i) =>
      s"""                    (${q(e.nodeId)}, ${i}u64) => ${i}u64,"""
    }.mkString("\n")
    val siteValidation = if (multiSite)
      s"""let __awfl_site = match (state.frames[1].node_id.as_str(), state.frames[1].iteration) {
$siteCases
                    _ => return Err("invalid native range continuation site".into()),
                };"""
    else "let __awfl_site = 0u64;"
    val itemsKey = q(s"__awfl_loop_${index}_items")
    val local = s"index_${loop.indexSlot}"
    val saved = prior.map(slot =>
      s"""                slots.insert(${q(slot)}.to_owned(), $slot.clone());""").mkString("\n")
    val body = loop.body.map {
      case e: Effect =>
        val site = direct.indexWhere(_.nodeId == e.nodeId)
        val resumedSite = if (site == 0) "" else s" && __awfl_resume_site >= ${site}u64"
        val savedEarlier = direct.take(site).map(previous =>
          s"""                slots.insert(${q(previous.resultSlot)}.to_owned(), ${previous.resultSlot}.clone());""").mkString("\n")
        val extraFrame = if (multiSite)
          s""", InvocationFrame {
                            node_id: ${q(e.nodeId)}.to_owned(),
                            iteration: ${site}u64,
                        }"""
        else ""
        s"""            // AWFL ${e.nodeId} ${e.displayName}: dynamic site $site
            let ${e.resultSlot} = if __awfl_resuming_here && __awfl_iter == __awfl_resume_iter$resumedSite {
                resume.ok_or("missing native continuation")?.slot(${q(e.resultSlot)})?
            } else {
                let mut slots = std::collections::BTreeMap::new();
$saved
$savedEarlier
                slots.insert($itemsKey.to_owned(), AwflValue::List(__awfl_items.clone()));
                return Ok(Outcome::Suspended {
                    effect: EffectIntent {
                        effect_id: format!("call_${index}_${e.resultSlot}_{}", __awfl_iter),
                        node_id: ${q(e.nodeId)}.to_owned(),
                        operation: ${q(e.id.wireId)}.to_owned(),
                        result_slot: ${q(e.resultSlot)}.to_owned(),
                        args: (${render(e.arguments, where)}).to_plain_json()?,
                    },
                    continuation: Continuation {
                        next_step: $index, slots,
                        frames: vec![InvocationFrame {
                            node_id: ${q(loop.nodeId)}.to_owned(),
                            iteration: __awfl_iter,
                        }$extraFrame],
                    },
                });
            };"""
      case other => emitStep(other, 12, effectful = true)
    }.mkString("\n")
    s"""    // AWFL ${loop.nodeId} ${loop.displayName} (resumable ForRange)
    let ${loop.resultSlot} = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(loop.resultSlot)})?
    } else {
        let __awfl_start = ops::int_value(&(${render(loop.from, where)}))?;
        let __awfl_end = ops::int_value(&(${render(loop.until, where)}))?;
        let __awfl_span = ((__awfl_end as i128) - (__awfl_start as i128)).max(0) as u64;
        let __awfl_resuming_here = resume.is_some_and(|s| !s.frames.is_empty() && pc == $index);
        let (__awfl_resume_iter, __awfl_resume_site, mut __awfl_items): (u64, u64, Vec<AwflValue>) =
            if __awfl_resuming_here {
                let state = resume.ok_or("missing native continuation")?;
                if state.frames.len() != $expectedFrames || state.frames[0].node_id != ${q(loop.nodeId)} {
                    return Err("invalid native range continuation frame".into());
                }
                let iteration = state.frames[0].iteration;
                let items = ops::list_value(&state.slot($itemsKey)?)?;
                if iteration >= __awfl_span || items.len() as u64 != iteration {
                    return Err("invalid native range continuation prefix".into());
                }
                $siteValidation
                (iteration, __awfl_site, items)
            } else { (0, 0, Vec::new()) };
        let __awfl_next = i64::try_from((__awfl_start as i128) + (__awfl_resume_iter as i128))?;
        for $local in __awfl_next..__awfl_end {
            let __awfl_iter = (($local as i128) - (__awfl_start as i128)) as u64;
            let ${loop.indexSlot} = AwflValue::Integer($local);
$body
            __awfl_items.push(${render(loop.result, where)});
        }
        AwflValue::List(__awfl_items)
    };"""
  }

  /** The bounded two-level range case: a single inner ForRange step with
    * direct Calls per inner iteration. Both completed output prefixes are
    * checkpointed, so neither loop replays completed effects on recovery.
    */
  private def emitNestedEffectRange(outer: RepeatRange, index: Int,
                                    prior: Vector[String]): String = {
    val where = s"node=${outer.nodeId} step='${outer.displayName}'"
    val inner = outer.body match {
      case Vector(r: RepeatRange) => r
      case _ => throw CompileError(where,
        "effectful nested ForRange requires exactly one inner ForRange step")
    }
    val nested = Program("inner-range", inner.body, Vector.empty).effects
    val direct = inner.body.collect { case e: Effect => e }
    if (direct.isEmpty || direct.size != nested.size || inner.body.size != direct.size)
      throw CompileError(where,
        "effectful nested ForRange requires only direct Calls in inner body")
    val outerItemsKey = q(s"__awfl_loop_${index}_items")
    val innerItemsKey = q(s"__awfl_loop_${index}_inner_items")
    val outerLocal = s"index_${outer.indexSlot}"
    val innerLocal = s"index_${inner.indexSlot}"
    val innerWhere = s"node=${inner.nodeId} step='${inner.displayName}'"
    val savedTop = prior.map(slot =>
      s"""                        slots.insert(${q(slot)}.to_owned(), $slot.clone());""").mkString("\n")
    val legalSites = direct.zipWithIndex.map { (e, site) =>
      s"""                        (${q(e.nodeId)}, ${site}u64) => ${site}u64,"""
    }.mkString("\n")
    val effects = direct.zipWithIndex.map { (e, site) =>
      val resumedSite = if (site == 0) "" else s" && __awfl_resume_site >= ${site}u64"
      val savedEarlier = direct.take(site).map(previous =>
        s"""                        slots.insert(${q(previous.resultSlot)}.to_owned(), ${previous.resultSlot}.clone());""").mkString("\n")
      s"""                // AWFL ${e.nodeId} ${e.displayName}
                let ${e.resultSlot} = if __awfl_resume_here && __awfl_outer_ord == __awfl_resume_outer && __awfl_inner_ord == __awfl_resume_inner$resumedSite {
                    resume.ok_or("missing native continuation")?.slot(${q(e.resultSlot)})?
                } else {
                    let mut slots = std::collections::BTreeMap::new();
$savedTop
$savedEarlier
                    slots.insert($outerItemsKey.to_owned(), AwflValue::List(__awfl_outer_items.clone()));
                    slots.insert($innerItemsKey.to_owned(), AwflValue::List(__awfl_inner_items.clone()));
                    return Ok(Outcome::Suspended {
                        effect: EffectIntent {
                            effect_id: format!("call_${index}_${e.resultSlot}_{}_{}", __awfl_outer_ord, __awfl_inner_ord),
                            node_id: ${q(e.nodeId)}.to_owned(),
                            operation: ${q(e.id.wireId)}.to_owned(),
                            result_slot: ${q(e.resultSlot)}.to_owned(),
                            args: (${render(e.arguments, innerWhere)}).to_plain_json()?,
                        },
                        continuation: Continuation {
                            next_step: $index, slots,
                            frames: vec![
                                InvocationFrame { node_id: ${q(outer.nodeId)}.to_owned(), iteration: __awfl_outer_ord },
                                InvocationFrame { node_id: ${q(inner.nodeId)}.to_owned(), iteration: __awfl_inner_ord },
                                InvocationFrame { node_id: ${q(e.nodeId)}.to_owned(), iteration: ${site}u64 },
                            ],
                        },
                    });
                };"""
    }.mkString("\n")
    s"""    // AWFL ${outer.nodeId} ${outer.displayName} (nested resumable ForRange)
    let ${outer.resultSlot} = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(outer.resultSlot)})?
    } else {
        let __awfl_outer_start = ops::int_value(&(${render(outer.from, where)}))?;
        let __awfl_outer_end = ops::int_value(&(${render(outer.until, where)}))?;
        let __awfl_outer_span = ((__awfl_outer_end as i128) - (__awfl_outer_start as i128)).max(0) as u64;
        let __awfl_resume_here = resume.is_some_and(|s| !s.frames.is_empty() && pc == $index);
        let (__awfl_resume_outer, __awfl_resume_inner, __awfl_resume_site,
             mut __awfl_outer_items, __awfl_saved_inner_items): (u64, u64, u64, Vec<AwflValue>, Vec<AwflValue>) =
            if __awfl_resume_here {
                let state = resume.ok_or("missing native continuation")?;
                if state.frames.len() != 3 || state.frames[0].node_id != ${q(outer.nodeId)} ||
                    state.frames[1].node_id != ${q(inner.nodeId)} {
                    return Err("invalid nested range continuation frame".into());
                }
                let outer_ordinal = state.frames[0].iteration;
                let inner_ordinal = state.frames[1].iteration;
                let outer_items = ops::list_value(&state.slot($outerItemsKey)?)?;
                let inner_items = ops::list_value(&state.slot($innerItemsKey)?)?;
                if outer_ordinal >= __awfl_outer_span ||
                    outer_items.len() as u64 != outer_ordinal ||
                    inner_items.len() as u64 != inner_ordinal {
                    return Err("invalid nested range continuation prefix".into());
                }
                let site = match (state.frames[2].node_id.as_str(), state.frames[2].iteration) {
$legalSites
                    _ => return Err("invalid nested range continuation site".into()),
                };
                (outer_ordinal, inner_ordinal, site, outer_items, inner_items)
            } else { (0, 0, 0, Vec::new(), Vec::new()) };
        let __awfl_outer_next = i64::try_from((__awfl_outer_start as i128) + (__awfl_resume_outer as i128))?;
        for $outerLocal in __awfl_outer_next..__awfl_outer_end {
            let __awfl_outer_ord = (($outerLocal as i128) - (__awfl_outer_start as i128)) as u64;
            let ${outer.indexSlot} = AwflValue::Integer($outerLocal);
            let __awfl_inner_start = ops::int_value(&(${render(inner.from, innerWhere)}))?;
            let __awfl_inner_end = ops::int_value(&(${render(inner.until, innerWhere)}))?;
            let __awfl_inner_span = ((__awfl_inner_end as i128) - (__awfl_inner_start as i128)).max(0) as u64;
            let __awfl_resume_this_outer = __awfl_resume_here && __awfl_outer_ord == __awfl_resume_outer;
            if __awfl_resume_this_outer && __awfl_resume_inner >= __awfl_inner_span {
                return Err("invalid nested range continuation iteration".into());
            }
            let mut __awfl_inner_items = if __awfl_resume_this_outer {
                __awfl_saved_inner_items.clone()
            } else { Vec::new() };
            let __awfl_inner_ordinal = if __awfl_resume_this_outer { __awfl_resume_inner } else { 0 };
            let __awfl_inner_next = i64::try_from((__awfl_inner_start as i128) + (__awfl_inner_ordinal as i128))?;
            for $innerLocal in __awfl_inner_next..__awfl_inner_end {
                let __awfl_inner_ord = (($innerLocal as i128) - (__awfl_inner_start as i128)) as u64;
                let ${inner.indexSlot} = AwflValue::Integer($innerLocal);
$effects
                __awfl_inner_items.push(${render(inner.result, innerWhere)});
            }
            let ${inner.resultSlot} = AwflValue::List(__awfl_inner_items);
            __awfl_outer_items.push(${render(outer.result, where)});
        }
        AwflValue::List(__awfl_outer_items)
    };"""
  }

  /** A resumable top-level Switch records the selected branch and the Call
    * site as two invocation frames. Branch selection is never re-evaluated
    * after suspension, and results of preceding Calls are restored by slot.
    *
    * Pure branches retain ordinary lowering. Effectful branches are limited
    * to a sequence of direct Calls until nested live-slot capture exists.
    */
  private def emitEffectChoice(choice: Choose, index: Int,
                               prior: Vector[String]): String = {
    val where = s"node=${choice.nodeId} step='${choice.displayName}'"
    val cases = choice.cases.map { branch =>
      val effects = Program("branch", branch.body, Vector.empty).effects
      val direct = branch.body.collect { case e: Effect => e }
      if (effects.nonEmpty && effects.size != branch.body.size)
        throw CompileError(where, "effectful Switch branch must contain only direct Calls; nested control flow or mixed steps unsupported")
      if (effects.nonEmpty && direct.size != effects.size)
        throw CompileError(where, "effectful Switch branch has nested Call sites")
      direct
    }
    val sites = cases.zipWithIndex.flatMap { (effects, branchIndex) =>
      effects.zipWithIndex.map { (e, site) =>
        s"""                    (${branchIndex}u64, ${q(e.nodeId)}, ${site}u64) => (${branchIndex}u64, ${site}u64),"""
      }
    }.mkString("\n")
    val savedTop = prior.map(slot =>
      s"""                slots.insert(${q(slot)}.to_owned(), $slot.clone());""").mkString("\n")
    val branches = choice.cases.zipWithIndex.map { (branch, branchIndex) =>
      val prefix = if (branchIndex == 0) "if" else "else if"
      val guard = s"(__awfl_resuming_here && __awfl_resume_branch == ${branchIndex}u64) || (!__awfl_resuming_here && ops::bool_value(&(${render(branch.condition, where)}))?)"
      val body = branch.body.map {
        case effect: Effect =>
          val site = cases(branchIndex).indexWhere(_.nodeId == effect.nodeId)
          val resumedSite = if (site == 0) "" else s" && __awfl_resume_site >= ${site}u64"
          val previous = cases(branchIndex).take(site)
          val slotsBinding = if (prior.isEmpty && previous.isEmpty) "let slots" else "let mut slots"
          val savedPrevious = previous.map(e =>
            s"""                slots.insert(${q(e.resultSlot)}.to_owned(), ${e.resultSlot}.clone());""").mkString("\n")
          s"""            // AWFL ${effect.nodeId} ${effect.displayName}
            let ${effect.resultSlot} = if __awfl_resuming_here$resumedSite {
                resume.ok_or("missing native continuation")?.slot(${q(effect.resultSlot)})?
            } else {
                $slotsBinding = std::collections::BTreeMap::new();
$savedTop
$savedPrevious
                return Ok(Outcome::Suspended {
                    effect: EffectIntent {
                        effect_id: ${q(s"call_${index}_${effect.resultSlot}_b${branchIndex}")}.to_owned(),
                        node_id: ${q(effect.nodeId)}.to_owned(),
                        operation: ${q(effect.id.wireId)}.to_owned(),
                        result_slot: ${q(effect.resultSlot)}.to_owned(),
                        args: (${render(effect.arguments, where)}).to_plain_json()?,
                    },
                    continuation: Continuation {
                        next_step: $index, slots,
                        frames: vec![
                            InvocationFrame { node_id: ${q(choice.nodeId)}.to_owned(), iteration: ${branchIndex}u64 },
                            InvocationFrame { node_id: ${q(effect.nodeId)}.to_owned(), iteration: ${site}u64 },
                        ],
                    },
                });
            };"""
        case pure => emitStep(pure, 12, effectful = true)
      }.mkString("\n")
      s"""$prefix $guard {
$body
            ${render(branch.result, where)}
        }"""
    }.mkString(" ")
    val assigned = if (branches.isEmpty)
      "AwflValue::Map(std::collections::BTreeMap::new())"
    else s"$branches else { AwflValue::Map(std::collections::BTreeMap::new()) }"
    s"""    // AWFL ${choice.nodeId} ${choice.displayName} (resumable Switch)
    let ${choice.resultSlot} = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(choice.resultSlot)})?
    } else {
        let __awfl_resuming_here = resume.is_some_and(|s| !s.frames.is_empty() && pc == $index);
        let (__awfl_resume_branch, __awfl_resume_site) = if __awfl_resuming_here {
            let state = resume.ok_or("missing native continuation")?;
            if state.frames.len() != 2 || state.frames[0].node_id != ${q(choice.nodeId)} {
                return Err("invalid native Switch continuation frame".into());
            }
            match (state.frames[0].iteration, state.frames[1].node_id.as_str(), state.frames[1].iteration) {
$sites
                _ => return Err("invalid native Switch continuation site".into()),
            }
        } else { (0u64, 0u64) };
        $assigned
    };"""
  }

  /** One additional structural Switch depth, without replaying either
    * branch condition. A branch containing nested effects must contain
    * exactly its nested Switch; inner effectful branches contain direct Calls.
    * The three-frame path is validated against all legal static sites.
    */
  private def emitNestedEffectChoice(outer: Choose, index: Int,
                                     prior: Vector[String]): String = {
    val where = s"node=${outer.nodeId} step='${outer.displayName}'"
    val nested = outer.cases.zipWithIndex.flatMap { (branch, outerBranch) =>
      val effects = Program("outer-branch", branch.body, Vector.empty).effects
      if (effects.isEmpty) Vector.empty
      else branch.body match {
        case Vector(inner: Choose) =>
          inner.cases.foreach { innerBranch =>
            val found = Program("inner-branch", innerBranch.body, Vector.empty).effects
            if (found.nonEmpty && (innerBranch.body.size != found.size ||
                !innerBranch.body.forall(_.isInstanceOf[Effect])))
              throw CompileError(where,
                "nested effectful Switch branches require only direct Calls")
          }
          Vector((outerBranch, inner))
        case _ => throw CompileError(where,
          "nested effectful Switch requires exactly one inner Switch in its outer branch")
      }
    }
    if (nested.isEmpty) throw CompileError(where, "nested effectful Switch has no Call sites")
    val legalSites = nested.flatMap { (outerBranch, inner) =>
      inner.cases.zipWithIndex.flatMap { (branch, innerBranch) =>
        branch.body.collect { case e: Effect => e }.zipWithIndex.map { (e, site) =>
          s"""                (${outerBranch}u64, ${q(inner.nodeId)}, ${innerBranch}u64, ${q(e.nodeId)}, ${site}u64) =>
                    (${outerBranch}u64, ${innerBranch}u64, ${site}u64),"""
        }
      }
    }.mkString("\n")
    val savedTop = prior.map(slot =>
      s"""                        slots.insert(${q(slot)}.to_owned(), $slot.clone());""").mkString("\n")
    val outerBranches = outer.cases.zipWithIndex.map { (branch, outerBranch) =>
      val prefix = if (outerBranch == 0) "if" else "else if"
      val guard = s"(__awfl_nested_resume && __awfl_outer_branch == ${outerBranch}u64) || (!__awfl_nested_resume && ops::bool_value(&(${render(branch.condition, where)}))?)"
      val body = branch.body match {
        case Vector(inner: Choose) if Program("inner", inner.cases.flatMap(_.body), Vector.empty).effects.nonEmpty =>
          val innerWhere = s"node=${inner.nodeId} step='${inner.displayName}'"
          val innerBranches = inner.cases.zipWithIndex.map { (innerBranch, innerBranchIndex) =>
            val innerPrefix = if (innerBranchIndex == 0) "if" else "else if"
            val innerGuard = s"(__awfl_nested_resume && __awfl_inner_branch == ${innerBranchIndex}u64) || (!__awfl_nested_resume && ops::bool_value(&(${render(innerBranch.condition, innerWhere)}))?)"
            val effects = innerBranch.body.collect { case e: Effect => e }
            val children = innerBranch.body.map {
              case e: Effect =>
                val site = effects.indexWhere(_.nodeId == e.nodeId)
                val savedPrevious = effects.take(site).map(previous =>
                  s"""                        slots.insert(${q(previous.resultSlot)}.to_owned(), ${previous.resultSlot}.clone());""").mkString("\n")
                val slotsBinding = if (prior.isEmpty && site == 0) "let slots" else "let mut slots"
                val resumeSite = if (site == 0) "" else s" && __awfl_call_site >= ${site}u64"
                s"""                    // AWFL ${e.nodeId} ${e.displayName}
                    let ${e.resultSlot} = if __awfl_nested_resume$resumeSite {
                        resume.ok_or("missing native continuation")?.slot(${q(e.resultSlot)})?
                    } else {
                        $slotsBinding = std::collections::BTreeMap::new();
$savedTop
$savedPrevious
                        return Ok(Outcome::Suspended {
                            effect: EffectIntent {
                                effect_id: ${q(s"call_${index}_${e.resultSlot}_b${outerBranch}_b${innerBranchIndex}")}.to_owned(),
                                node_id: ${q(e.nodeId)}.to_owned(),
                                operation: ${q(e.id.wireId)}.to_owned(),
                                result_slot: ${q(e.resultSlot)}.to_owned(),
                                args: (${render(e.arguments, innerWhere)}).to_plain_json()?,
                            },
                            continuation: Continuation {
                                next_step: $index, slots,
                                frames: vec![
                                    InvocationFrame { node_id: ${q(outer.nodeId)}.to_owned(), iteration: ${outerBranch}u64 },
                                    InvocationFrame { node_id: ${q(inner.nodeId)}.to_owned(), iteration: ${innerBranchIndex}u64 },
                                    InvocationFrame { node_id: ${q(e.nodeId)}.to_owned(), iteration: ${site}u64 },
                                ],
                            },
                        });
                    };"""
              case pure => emitStep(pure, 20, effectful = true)
            }.mkString("\n")
            s"""$innerPrefix $innerGuard {
$children
                    ${render(innerBranch.result, innerWhere)}
                }"""
          }.mkString(" ")
          val innerResult = if (innerBranches.isEmpty)
            "AwflValue::Map(std::collections::BTreeMap::new())"
          else s"$innerBranches else { AwflValue::Map(std::collections::BTreeMap::new()) }"
          s"""            let ${inner.resultSlot} = $innerResult;"""
        case _ => branch.body.map(pure => emitStep(pure, 12, effectful = true)).mkString("\n")
      }
      s"""$prefix $guard {
$body
            ${render(branch.result, where)}
        }"""
    }.mkString(" ")
    val assigned = if (outerBranches.isEmpty)
      "AwflValue::Map(std::collections::BTreeMap::new())"
    else s"$outerBranches else { AwflValue::Map(std::collections::BTreeMap::new()) }"
    s"""    // AWFL ${outer.nodeId} ${outer.displayName} (nested resumable Switch)
    let ${outer.resultSlot} = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(outer.resultSlot)})?
    } else {
        let __awfl_nested_resume = resume.is_some_and(|s| !s.frames.is_empty() && pc == $index);
        let (__awfl_outer_branch, __awfl_inner_branch, __awfl_call_site) =
            if __awfl_nested_resume {
                let state = resume.ok_or("missing native continuation")?;
                if state.frames.len() != 3 || state.frames[0].node_id != ${q(outer.nodeId)} {
                    return Err("invalid nested Switch continuation frame".into());
                }
                match (
                    state.frames[0].iteration, state.frames[1].node_id.as_str(),
                    state.frames[1].iteration, state.frames[2].node_id.as_str(),
                    state.frames[2].iteration,
                ) {
$legalSites
                    _ => return Err("invalid nested Switch continuation site".into()),
                }
            } else { (0u64, 0u64, 0u64) };
        $assigned
    };"""
  }

  /** Direct Rust computation with server-managed effect boundaries. */
  def effectfulSource(program: Program): String = {
    val frameConditions = program.steps.zipWithIndex.collect {
      case (loop: RepeatRange, i) if Program("range-effects", loop.body, Vector.empty).effects.nonEmpty =>
        val hasInnerRange = loop.body.exists {
          case inner: RepeatRange =>
            Program("inner", inner.body, Vector.empty).effects.nonEmpty
          case _ => false
        }
        val frames = if (hasInnerRange) 3
          else if (loop.body.collect { case _: Effect => 1 }.size > 1) 2
          else 1
        s"pc == $i && s.frames.len() == $frames && s.frames[0].node_id == ${q(loop.nodeId)}"
      case (choice: Choose, i) if Program("choice-effects", choice.cases.flatMap(_.body), Vector.empty).effects.nonEmpty =>
        val nested = choice.cases.exists { c =>
          c.body.exists {
            case inner: Choose =>
              Program("inner-effects", inner.cases.flatMap(_.body), Vector.empty).effects.nonEmpty
            case _ => false
          }
        }
        s"pc == $i && s.frames.len() == ${if (nested) 3 else 2} && s.frames[0].node_id == ${q(choice.nodeId)}"
    }
    val frameTargets = frameConditions match {
      case Vector() => "false"
      case Vector(one) => one
      case many => many.map(expr => s"($expr)").mkString(" || ")
    }
    val statements = program.steps.zipWithIndex.map { (step, index) =>
      val where = s"node=${step.nodeId} step='${step.displayName}'"
      step match {
        case Finish(_, _, value) =>
          s"""    Ok(Outcome::Completed { result: (${render(value, where)}).to_plain_json()? })"""
        case effect: Effect =>
          val prior = program.steps.take(index).flatMap(topLevelSlot)
          val slotsBinding = if (prior.isEmpty) "let slots" else "let mut slots"
          val saved = prior.map(slot =>
            s"""            slots.insert(${q(slot)}.to_owned(), $slot.clone());""").mkString("\n")
          s"""    // AWFL ${effect.nodeId} ${effect.displayName}
    let ${effect.resultSlot} = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(effect.resultSlot)})?
    } else {
        $slotsBinding = std::collections::BTreeMap::new();
$saved
        return Ok(Outcome::Suspended {
            effect: EffectIntent {
                effect_id: ${q(s"call_$index")}.to_owned(),
                node_id: ${q(effect.nodeId)}.to_owned(),
                operation: ${q(effect.id.wireId)}.to_owned(),
                result_slot: ${q(effect.resultSlot)}.to_owned(),
                args: (${render(effect.arguments, where)}).to_plain_json()?,
            },
            continuation: Continuation { next_step: ${index + 1}, slots, frames: vec![] },
        });
    };"""
        case early: EarlyReturn =>
          s"""    if pc <= $index {
${emitStep(early, 8, effectful = true)}
    }"""
        case loop: RepeatRange =>
          val slot = loop.resultSlot
          val prior = program.steps.take(index).flatMap(topLevelSlot)
          if (Program("range-effects", loop.body, Vector.empty).effects.nonEmpty) {
            val hasInnerRange = loop.body.exists {
              case inner: RepeatRange =>
                Program("inner", inner.body, Vector.empty).effects.nonEmpty
              case _ => false
            }
            if (hasInnerRange) emitNestedEffectRange(loop, index, prior)
            else emitEffectRange(loop, index, prior)
          }
          else s"""    let $slot = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(slot)})?
    } else {
${emitStep(loop, 8, effectful = true)}
        $slot
    };"""
        case choice: Choose if Program("choice-effects", choice.cases.flatMap(_.body), Vector.empty).effects.nonEmpty =>
          val prior = program.steps.take(index).flatMap(topLevelSlot)
          val nested = choice.cases.exists(_.body.exists {
            case inner: Choose =>
              Program("inner-effects", inner.cases.flatMap(_.body), Vector.empty).effects.nonEmpty
            case _ => false
          })
          if (nested) emitNestedEffectChoice(choice, index, prior)
          else emitEffectChoice(choice, index, prior)
        case other =>
          val slot = topLevelSlot(other).getOrElse(
            throw CompileError(where, "unsupported top-level native statement"))
          s"""    let $slot = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(slot)})?
    } else {
${emitStep(other, 8, effectful = true)}
        $slot
    };"""
      }
    }.mkString("\n")

    s"""// Generated by awfl compiler-rust Scala frontend. Do not edit.
use std::io::{self, Read};
use awfl_native_runtime::AwflValue;
#[allow(unused_imports)]
use awfl_native_runtime::operations as ops;
#[allow(unused_imports)]
use awfl_native_runtime::protocol::{
    Request, Response, Outcome, EffectIntent, Continuation, InvocationFrame,
};

// Generated lexical scopes intentionally bind and return the same AWFL slot.
// The DSL may declare loop index slots that user code never reads.
#[allow(clippy::let_and_return, unreachable_code, unused_variables)]
fn run(input: &AwflValue, resume: Option<&Continuation>) -> Result<Outcome, Box<dyn std::error::Error>> {
    let _ = input;
    let pc = resume.map_or(0, |state| state.next_step);
    if resume.is_some_and(|s| {
        let valid_target = $frameTargets;
        !s.frames.is_empty() && !valid_target
    }) {
        return Err("invalid native continuation frame target".into());
    }
$statements
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut text = String::new();
    io::stdin().read_to_string(&mut text)?;
    let request: Request = serde_json::from_str(&text)?;
    request.validate(${program.steps.size}).map_err(|e|
        io::Error::new(io::ErrorKind::InvalidInput, e))?;
    let input = AwflValue::from_plain_json(&request.input)?;
    let outcome = run(&input, request.continuation.as_ref())?;
    println!("{}", serde_json::to_string(&Response::new(outcome))?);
    Ok(())
}
"""
  }

}
