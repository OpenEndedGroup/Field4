import {makeScene2D, Rect, Txt} from '@motion-canvas/2d';
import {beginSlide, createRef} from '@motion-canvas/core';

export default makeScene2D(function* (view) {
  view.fill('#050505');
  const title = createRef<Txt>();
  const line = createRef<Rect>();
  view.add(<>
    <Txt ref={title} text="FIELD / PRESENT" fontFamily="Iosevka, monospace"
      fontSize={112} fill="#f4f4f4" opacity={0}/>
    <Rect ref={line} y={100} width={0} height={2} fill="#e7e7e7"/>
  </>);
  yield* title().opacity(1, 0.8);
  yield* line().width(940, 0.7);
  yield* beginSlide('intro');
});
