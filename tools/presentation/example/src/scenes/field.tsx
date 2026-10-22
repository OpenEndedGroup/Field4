import {makeScene2D, Txt} from '@motion-canvas/2d';
import {beginSlide} from '@motion-canvas/core';

export default makeScene2D(function* (view) {
  view.fill('#050505');
  view.add(<Txt text="Now, the actual Field document" fontFamily="Iosevka, monospace"
    fontSize={64} fill="#f4f4f4" y={-440}/>);
  yield* beginSlide('field');
});
