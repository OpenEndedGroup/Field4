import {makeProject} from '@motion-canvas/core';
import intro from './scenes/intro?scene';
import browser from './scenes/browser?scene';
import video from './scenes/video?scene';
import close from './scenes/close?scene';

export default makeProject({scenes: [intro, browser, video, close]});
