/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.intentions.branchedTransformations.intentions

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.idea.inspections.branchedTransformations.IfThenToSafeAccessInspection
import org.jetbrains.kotlin.idea.intentions.SelfTargetingOffsetIndependentIntention
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.*
import org.jetbrains.kotlin.idea.util.CommentSaver
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.bindingContextUtil.isUsedAsExpression
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class IfThenToElvisInspection : IntentionBasedInspection<KtIfExpression>(
    IfThenToElvisIntention::class,
    { it -> it.isUsedAsExpression(it.analyze(BodyResolveMode.PARTIAL_WITH_CFA)) }
) {
    override fun inspectionTarget(element: KtIfExpression) = element.ifKeyword

    override fun problemHighlightType(element: KtIfExpression): ProblemHighlightType =
        if (element.shouldBeTransformed()) super.problemHighlightType(element) else ProblemHighlightType.INFORMATION
}

class IfThenToElvisIntention : SelfTargetingOffsetIndependentIntention<KtIfExpression>(KtIfExpression::class.java, intentionText) {

    override fun isApplicableTo(element: KtIfExpression): Boolean = isApplicableTo(element, expressionShouldBeStable = true)

    override fun startInWriteAction() = false

    override fun applyTo(element: KtIfExpression, editor: Editor?) = convert(element, editor)

    companion object {
        private fun KtExpression.isNullOrBlockExpression(): Boolean {
            val innerExpression = this.unwrapBlockOrParenthesis()
            return innerExpression is KtBlockExpression || innerExpression.node.elementType == KtNodeTypes.NULL
        }

        private fun IfThenToSelectData.clausesReplaceableByElvis(): Boolean =
            when {
                baseClause == null || negatedClause == null || negatedClause.isNullOrBlockExpression() ->
                    false
                negatedClause is KtThrowExpression && negatedClause.throwsNullPointerExceptionWithNoArguments() ->
                    false
                baseClause.evaluatesTo(receiverExpression) ->
                    true
                baseClause.anyArgumentEvaluatesTo(receiverExpression) ->
                    true
                hasImplicitReceiverReplaceableBySafeCall() || baseClause.hasFirstReceiverOf(receiverExpression) ->
                    !baseClause.hasNullableType(context)
                else ->
                    false
            }

        val intentionText = "Replace 'if' expression with elvis expression"

        fun convert(element: KtIfExpression, editor: Editor?) {
            val ifThenToSelectData = element.buildSelectTransformationData() ?: return

            val factory = KtPsiFactory(element)

            val commentSaver = CommentSaver(element, saveLineBreaks = true)

            val elvis = runWriteAction {
                val replacedBaseClause = ifThenToSelectData.replacedBaseClause(factory)
                val newExpr = element.replaced(
                    factory.createExpressionByPattern(
                        "$0 ?: $1",
                        replacedBaseClause,
                        ifThenToSelectData.negatedClause!!
                    )
                )

                (KtPsiUtil.deparenthesize(newExpr) as KtBinaryExpression).also {
                    commentSaver.restore(it)
                }
            }

            if (editor != null) {
                elvis.inlineLeftSideIfApplicableWithPrompt(editor)
                with(IfThenToSafeAccessInspection) {
                    (elvis.left as? KtSafeQualifiedExpression)?.renameLetParameter(editor)
                }
            }
        }

        fun isApplicableTo(element: KtIfExpression, expressionShouldBeStable: Boolean): Boolean {
            val ifThenToSelectData = element.buildSelectTransformationData() ?: return false
            if (expressionShouldBeStable && !ifThenToSelectData.receiverExpression.isStableSimpleExpression(ifThenToSelectData.context)) return false

            val type = element.getType(ifThenToSelectData.context) ?: return false
            if (KotlinBuiltIns.isUnit(type)) return false

            return ifThenToSelectData.clausesReplaceableByElvis()
        }
    }
}
