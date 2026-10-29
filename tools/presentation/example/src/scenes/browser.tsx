import {makeScene2D, Txt} from '@motion-canvas/2d';
import {beginSlide} from '@motion-canvas/core';

export default makeScene2D(function* (view) {
  view.fill('#050505');
  view.add(<Txt text="A live web page" fontFamily="Iosevka, monospace"
    fontSize={72} fill="#f4f4f4" y={-430}/>);
  yield* beginSlide('web');
});
