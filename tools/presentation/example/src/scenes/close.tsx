import {makeScene2D, Txt} from '@motion-canvas/2d';
import {beginSlide} from '@motion-canvas/core';

export default makeScene2D(function* (view) {
  view.fill('#050505');
  view.add(<Txt text="Questions?" fontFamily="Iosevka, monospace"
    fontSize={112} fill="#f4f4f4"/>);
  yield* beginSlide('close');
});
