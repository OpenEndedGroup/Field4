<<you>>
Sketch a small garden shed, 3 m by 2 m, with one window.
<<ai>>
<<model:{"provider":"example","requestedModel":"example-model","responseModel":"example-model"}>>
Starting with the footprint: four walls, a door on the long side.
<<progress>>
Waiting for model
<<progress>>
_field_sketch_edit_e0b4aeb5c8da0381fe14367f finished (1400 ms)
revision 1
<<progress>>
ViewBoxOutputRequest finished (850 ms)
One image
<<ai>>
<<model:{"provider":"example","requestedModel":"example-model","responseModel":"example-model"}>>
_Output of box 'Shed plan' (main), 360x240 — looking for: the footprint, door and window_

![tool image](ai4img:bf20b2eb6ccb2b8e.png)
<<ai>>
<<model:{"provider":"example","requestedModel":"example-model","responseModel":"example-model"}>>
The footprint is 3 × 2 m. The door is on the south wall, the window in the east wall.
<<you>>
Add a pitched roof, ridge along the long side.
<<progress>>
RunToolProgramRequest finished (2100 ms)
- ✓ `DocumentGuideAppend` `{"path":"docs/shed.md"}` — 6 ms
- ✓ `_field_sketch_edit_e0b4aeb5c8da0381fe14367f` `{"edit":"roof"}` — 1800 ms
<<progress>>
StartStudyBuildRequest finished (35 ms)
started
<<progress>>
WaitStudyBuildRequest finished (4200 ms)
done
<<progress>>
ViewBoxOutputRequest finished (900 ms)
One image
<<ai>>
<<model:{"provider":"example","requestedModel":"example-model","responseModel":"example-model"}>>
_Output of box 'Shed plan' (main), 360x240 — looking for: the ridge line and rafters_

![tool image](ai4img:29e5c776334b7673.png)
<<ai>>
<<model:{"provider":"example","requestedModel":"example-model","responseModel":"example-model"}>>
The ridge runs east–west along the long side, rafters every 600 mm. I noted the roof pitch in the shed guide.
<<you>>
Make the window twice as wide.
<<progress>>
_field_sketch_edit_e0b4aeb5c8da0381fe14367f failed (300 ms)
window wider than the wall allows with the corner post
<<progress>>
_field_sketch_edit_e0b4aeb5c8da0381fe14367f finished (1250 ms)
revision 3
<<progress>>
ViewBoxOutputRequest finished (800 ms)
One image
<<ai>>
<<model:{"provider":"example","requestedModel":"example-model","responseModel":"example-model"}>>
_Output of box 'Shed plan' (main), 360x240 — looking for: the wider window_

![tool image](ai4img:47ffb14f63b2d700.png)
<<ai>>
<<model:{"provider":"example","requestedModel":"example-model","responseModel":"example-model"}>>
The window is now 900 mm wide. The first edit clashed with the corner post, so I moved the post 50 mm inward.
