package afield.graphics

import afield.FLineEditSpace
import afield.FLineHandleKind
import afield.FLinePointRef
import afield.FLineTranslatePoints
import kotlin.test.*

class FLineCommitTest {
    @Test fun replacedViewerReleasesItsPreviousEditorAndCallback() {
        val line=FLine().moveTo(0.0,0.0,0.0).lineTo(1.0,0.0,0.0)
        val first=buildThreeGeometryPayload("commit-lifecycle",listOf(line),16,true,true)
        val second=buildThreeGeometryPayload("commit-lifecycle",listOf(line.copy()),16,true,true)
        val firstId=assertNotNull(first.scene).sessionId
        val secondId=assertNotNull(second.scene).sessionId
        val view=ThreeGeometryViewState("commit-lifecycle")
        view.update(first)
        assertNotNull(getFLineEditSession(firstId))
        view.update(second)
        assertNull(getFLineEditSession(firstId))
        assertNotNull(getFLineEditSession(secondId))
        view.update(second.copy(scene=null,editableEnabled=false))
        assertNull(getFLineEditSession(secondId))
    }

    @Test fun completedDragPublishesDetachedSnapshotAfterAllDeltas() {
        val line=FLine().moveTo(0.0,0.0,0.0).cubicTo(1.0,0.0,0.0,2.0,0.0,0.0,3.0,0.0,0.0)
        val commits=mutableListOf<List<FLine>>()
        val session=registerFLineEditSession(listOf(line),null) {commits.add(it)}
        val ref=FLinePointRef(0,1,FLineHandleKind.C1)
        repeat(3) {session.translate(FLineTranslatePoints(session.sessionId,listOf(ref),.5,.25,0.0,FLineEditSpace.MODEL))}
        assertTrue(commits.isEmpty())
        session.commit()
        val copied=commits.single().single().nodes[1] as FLine.CubicTo
        assertEquals(2.5f,copied.c1.x);assertEquals(.75f,copied.c1.y)
        session.translate(FLineTranslatePoints(session.sessionId,listOf(ref),1.0,0.0,0.0,FLineEditSpace.MODEL))
        assertEquals(2.5f,copied.c1.x)
        copied.c1.x=99f
        assertEquals(3.5f,(line.nodes[1] as FLine.CubicTo).c1.x)
    }
}
