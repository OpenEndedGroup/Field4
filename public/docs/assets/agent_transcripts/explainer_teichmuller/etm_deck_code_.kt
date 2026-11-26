import kotlinx.html.*

// The talk. Every figure is a box beside this one, named as in figureBeside(...).
presentation(title = "Teichmüller maps") {
    step("Teichmüller maps", id = "opening") {
        audience {
            +"""
                # Teichmüller maps

                How different are two leaves? One answer: bend one onto the other, and
                measure how much the bending had to distort it. Teichmüller maps are the
                bendings that distort as little as possible.

                This talk builds the idea from nothing: maps that keep angles, maps that
                turn circles into ellipses, the map with the least distortion, and the
                distance between shapes that comes out of it.""".md
            p(classes = "muted") {
                +"After the work of Gary P. T. Choi, L. Mahadevan, Lok Ming Lui and co-authors. Words and figures made in Field."
            }
        }
        markdownNote("Where we are going: a number for how different two outlines are. [[next]]")
    }

    section("Conformal maps") {
        step("Maps that keep angles", id = "conformal") {
            audience {
                figureBeside("circles") {
                    +"""
                        ## Maps that keep angles

                        A map moves every point of the plane somewhere else. This one bends
                        the grid, yet wherever two grid lines cross, they still cross at a
                        right angle.

                        A map that keeps every angle is called **conformal**. Look closely and
                        each small circle is still a circle: it has grown or shrunk and turned,
                        but kept its shape.""".md
                }
            }
            control("circles", "map", 0.0)
            control("circles", "amount", 0.0)
            cue("Bend") { control("circles", "amount", 0.9, glideMs = 2000) }
            markdownNote("Next bends the plane with z + a·z². The circles stay round and blue. [[back]] [[next]]")
        }
    }

    section("Quasi-conformal maps") {
        step("Circles become ellipses", id = "stretch") {
            audience {
                figureBeside("circles") {
                    +"""
                        ## Circles become ellipses

                        Most maps are not conformal. Stretch the plane sideways and every
                        small circle becomes an ellipse.

                        Seen closely enough, any smooth map is a stretch: a small enough
                        circle always comes out as an ellipse. A map whose ellipses never get
                        infinitely thin is **quasi-conformal**.""".md
                }
            }
            control("circles", "map", 1.0)
            control("circles", "amount", 0.0)
            cue("Stretch") { control("circles", "amount", 0.8, glideMs = 2000) }
            cue("Twist") {
                control("circles", "map", 2.0)
                control("circles", "amount", 0.8)
            }
            markdownNote(
                "Next stretches; the one after twists the middle, where the ellipses differ from place to place. [[back]] [[next]]",
            )
        }
        step("The Beltrami coefficient", id = "beltrami") {
            audience {
                figureBeside("ellipse") {
                    +"""
                        ## The Beltrami coefficient

                        One complex number says which ellipse a map makes at a point: the
                        **Beltrami coefficient** μ. Writing the map as f(z),
                        μ = (∂f/∂z̄) ÷ (∂f/∂z).

                        Its size, between 0 and 1, sets the shape: the ellipse is
                        K = (1 + |μ|) ÷ (1 − |μ|) times as long as it is wide. Its angle sets
                        the direction: half of it. μ = 0 is a circle, a conformal map.""".md
                }
            }
            control("ellipse", "size", 0.0)
            control("ellipse", "angle", 30.0)
            cue("Grow μ") { control("ellipse", "size", 0.6, glideMs = 1500) }
            cue("Turn μ") { control("ellipse", "angle", 210.0, glideMs = 2500) }
            markdownNote(
                "Next grows |μ| to 0.6 (K = 4); then turning μ by 180° turns the ellipse by 90°. [[back]] [[next]]",
            )
        }
        step("A μ at every point", id = "field") {
            audience {
                figureBeside("circles") {
                    +"""
                        ## A μ at every point

                        The twist has a different μ at every point. The colours are its
                        size: blue where circles stay round, orange where they stretch.

                        The remarkable fact runs the other way. Choose any μ, anywhere below 1,
                        and there is a map with exactly those ellipses, unique once you fix
                        where three points go. μ is a complete description of the map.""".md
                }
            }
            control("circles", "map", 2.0)
            control("circles", "amount", 0.9)
            markdownNote("The measurable Riemann mapping theorem, in one sentence. [[back]] [[next]]")
        }
    }

    section("Teichmüller maps") {
        step("From μ back to a map", id = "solver") {
            audience {
                figureBeside("landmark") {
                    +"""
                        ## From μ back to a map

                        On a computer the plane is a mesh of triangles and a map moves each
                        vertex. Given μ on every triangle, the **linear Beltrami solver**
                        finds the map: each coordinate solves one sparse linear system.

                        Here the square's edges are held and one point inside is dragged.
                        The left map asks for μ = 0, as conformal as it can be.""".md
                }
            }
            control("landmark", "landmark x", 0.3)
            control("landmark", "landmark y", 0.5)
            cue("Drag the point") {
                control("landmark", "landmark x", 0.65, glideMs = 2000)
                control("landmark", "landmark y", 0.6, glideMs = 2000)
            }
            markdownNote("Starts undisturbed; Next drags the white point to the orange one. [[back]] [[next]]")
        }
        step("No conformal map exists", id = "harmonic") {
            audience {
                figureBeside("landmark") {
                    +"""
                        ## No conformal map exists

                        Fix the edges and move a point, and no map can keep every angle.
                        The μ = 0 request gives the harmonic map on the left. Its
                        distortion piles up around the dragged point, and where |μ| passes 1
                        triangles turn over: the map folds.

                        So ask a better question: of all maps that move the point there,
                        which has the smallest worst ellipse?""".md
                }
            }
            control("landmark", "landmark x", 0.65)
            control("landmark", "landmark y", 0.6)
            markdownNote("Point out the orange ring round the landmark on the left. [[back]] [[next]]")
        }
        step("Spread the distortion evenly", id = "teichmuller") {
            audience {
                figureBeside("landmark") {
                    +"""
                        ## Spread the distortion evenly

                        That map is the **extremal** map, and Teichmüller showed it usually
                        has the same |μ| everywhere: only the direction of stretch changes.
                        Such a map is a **Teichmüller map**.

                        The right map is found by iterating: solve, measure μ, smooth it,
                        give every triangle the mean size, solve again (Lui, Lam, Yau and
                        Gu, 2014). Its colour is even, and its worst |μ| is lower.""".md
                }
            }
            control("landmark", "landmark x", 0.65)
            control("landmark", "landmark y", 0.6)
            cue("Further") {
                control("landmark", "landmark x", 0.7, glideMs = 2000)
                control("landmark", "landmark y", 0.38, glideMs = 2000)
            }
            markdownNote("Compare the two readouts. Next drags the landmark further. [[back]] [[next]]")
        }
    }

    section("Comparing shapes") {
        step("A distance between shapes", id = "distance") {
            audience {
                figureBeside("ellipse") {
                    +"""
                        ## A distance between shapes

                        A Teichmüller map has one number, K, its ellipses' shape. The
                        **Teichmüller distance** between two shapes is ½ log K of the
                        Teichmüller map from one to the other.

                        It is a true distance: zero only for shapes one conformal map
                        apart, the same both ways, and never shorter than going via a third
                        shape. Growing or shrinking a shape is conformal, so size is free.""".md
                }
            }
            control("ellipse", "size", 0.33)
            control("ellipse", "angle", 0.0)
            markdownNote("|μ| = 0.33 means K = 2 and distance ½ log 2 ≈ 0.35. [[back]] [[next]]")
        }
        step("Two leaves", id = "leaves") {
            audience {
                figureBeside("leaf_compare") {
                    +"""
                        ## Two leaves

                        Choi and Mahadevan compare planar shapes this way: leaves, insect
                        wings, the outlines of organs. Mark matching landmarks, let the
                        outline slide between them, find the Teichmüller map.

                        Here the landmarks are the tip, the base, and the margin a third and
                        two thirds of the way up. The colour is even: the same stretch
                        everywhere, only its direction changes. Choose two leaves.""".md
                }
            }
            control("leaf_compare", "first leaf", 0.0)
            control("leaf_compare", "second leaf", 3.0)
            cue("Lanceolate") { control("leaf_compare", "second leaf", 1.0) }
            markdownNote("Elliptic to heart-shaped; Next compares elliptic with lanceolate. [[back]] [[next]]")
        }
        step("A table of distances", id = "table") {
            audience {
                figureBeside("leaf_distances") {
                    +"""
                        ## A table of distances

                        Every pair of five leaves. With distances in hand, the usual tools
                        apply: nearest neighbours, clusters, a tree of similarity.

                        Biologists can compare outlines without choosing a coordinate
                        system or a scale, and see where on the shape the difference
                        lies.""".md
                }
            }
            markdownNote("The table takes a few seconds to compute on first show. [[back]] [[next]]")
        }
        step("What this version leaves out", id = "caveats") {
            audience {
                +"""
                    ## What this version leaves out

                    - Our leaves are mirror-symmetric, and we use it: the best map is then
                      symmetric too, each half is a four-cornered region, and the map between
                      two of those goes through rectangles, with an exact distance. Leaves
                      that are not symmetric need the general sliding-boundary iteration.
                    - With only four landmarks, tip, base and one pair, every symmetric leaf
                      is at distance zero from every other: the landmarks decide what the
                      distance can see.
                    - Our outlines are formulas; real ones come from photographs.
                    - The same machinery works on point clouds without a mesh (Choi, Liu
                      and Lui) and, with density instead of angle, gives density-equalizing
                      maps (Choi and Rycroft).""".md
            }
            markdownNote("Questions for Gary go here. [[back]] [[next]]")
        }
    }

    step("Further reading", id = "further-reading") {
        audience {
            +"""
                ## Further reading

                - Gary P. T. Choi and L. Mahadevan, “Planar morphometrics using Teichmüller maps”, *Proc. R. Soc. A* 474 (2018), doi 10.1098/rspa.2017.0905.
                - Lok Ming Lui, Ka Chun Lam, Shing-Tung Yau and Xianfeng Gu, “Teichmüller mapping (T-map) and its applications to landmark matching registration”, *SIAM J. Imaging Sciences* 7 (2014), doi 10.1137/120900186.
                - Gary P. T. Choi, Yechen Liu and Lok Ming Lui, “Free-boundary conformal parameterization of point clouds”, *J. Sci. Comput.* 90 (2022), doi 10.1007/s10915-021-01641-6.
                - Gary P. T. Choi and Chris H. Rycroft, “Density-equalizing maps for simply connected open surfaces”, *SIAM J. Imaging Sciences* 11 (2018), doi 10.1137/17M1124796.""".md
            p(classes = "muted") {
                +"After the work of Gary P. T. Choi, L. Mahadevan, Lok Ming Lui and co-authors. Words and figures made in Field."
            }
        }
        markdownNote("The end. [[back]] [[finish]]")
    }
}.arrangeBoxes().present()
