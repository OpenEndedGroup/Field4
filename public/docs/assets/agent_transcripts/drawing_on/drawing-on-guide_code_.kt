markdown(
    """
    # Drawing On
    Run **iPad drawing head** to publish the plan and watch its preview here.

    On a Mac, **./field.sh --setup** offers Drawing On and prepares its offline reader.
    Build the optional app in **native/field-drawingon/DrawingOn.xcodeproj**.
    Connect it to **/drawingon/ws**, target **ipad**, and enable **Stream live**.
    Finish a Pencil stroke: Field sends a crosshair at its last measured point.

    Change **roomWidth** and rerun to change the plan. Re-running replaces this
    box's callback. Give another independent head a different target name.

    For offline work leave streaming off, record drawing and optional voice,
    then **Finish capture**. Upload later from **Saved captures**, or share the
    **.ndjson** save and run **native/field-drawingon/tools/offline-review/review**
    on a Mac for contact sheets, speech clips and a manifest.

    [Setup, coordinates and offline review](https://openendedgroup.com/field4/drawing_on/)""".trimIndent(),
)
