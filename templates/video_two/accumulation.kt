// This screen's accumulator has its own module and history; Alt+R reloads only this module.
val view = carousel.surfaces[SCREEN_INDEX]
view.accumulationModuleFactory = { configuration -> moduleHere(configuration) }
