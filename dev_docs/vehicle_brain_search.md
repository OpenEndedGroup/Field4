# Vehicle brain search: evolution vs random sampling (measured 2026-11-03)

Backs `public/docs/braitenberg_vehicles.md` §10. Code: `src/jvmMain/kotlin/afield/simulation/agents/alife_vehicle_brains.kt`
(`VehicleBrainGenome`, `VehicleBrainTask`, `compareVehicleSearch`); test:
`src/jvmTest/kotlin/afield/simulation/agents/VehicleBrainSearchTest.kt`.

Reproduce:

```
./gradlew jvmTest --tests 'afield.simulation.agents.VehicleBrainSearchTest.fullSweep' -Dafield.vehicleBrainSweep=true --rerun
./gradlew jvmTest --tests 'afield.simulation.agents.VehicleBrainSearchTest.fullSweep' -Dafield.vehicleBrainSweep=immigrants --rerun
```

Each writes `build/vehicle-brain-sweep-<mode>.txt`. Budget 300 objective calls per run, seeds 1..12,
10 vehicles x 1500 steps per evaluation (last 500 scored).

Finding: the §9 evolver setting (mu 6, lambda 12, no immigrants) and the hill climber lose to random
sampling on the median at almost every genome size; both are bimodal (a few seeds find the best
genomes of any arm, the rest stay on a flat ~0.1 plateau where vehicles never return to the lamp).
mu 12, lambda 24 with immigrantRate 0.25 beats random at 8-11 of 12 seeds on every task and size
measured (8, 44, 344 genes). Genome size is not where it flips; escaping the plateau is.

Not done: budgets other than 300; immigrant setting at 22 and 112 genes; novelty search / MAP-Elites
on the same genome.

## Setting `true` (mu 6, lambda 12, crossover 0.3)

```
forage, 0 hidden (8 genes), 300 evals x 12 seeds
  random     median 0.5080  IQR 0.4341..0.5740  range 0.2498..0.6533
  hill_climb median 0.3855  IQR 0.3249..0.6767  range 0.2769..0.9047  beats random at 5/12
  evolve     median 0.3049  IQR 0.2906..0.3518  range 0.2744..0.9001  beats random at 3/12
seed    random  hillclimb   evolve
   1    0.2597    0.9047    0.9001
   2    0.4126    0.6865    0.3036
   3    0.5442    0.3675    0.3380
   4    0.6395    0.3345    0.2744
   5    0.4412    0.6378    0.2813
   6    0.4903    0.3257    0.3230
   7    0.5581    0.4035    0.3061
   8    0.5258    0.3224    0.3933
   9    0.6533    0.6734    0.2973
  10    0.2498    0.8450    0.2931
  11    0.4451    0.2969    0.2830
  12    0.6216    0.2769    0.7018

forage, 2 hidden (22 genes), 300 evals x 12 seeds
  random     median 0.6100  IQR 0.5520..0.6783  range 0.3664..0.8148
  hill_climb median 0.3585  IQR 0.2841..0.5646  range 0.2718..0.6900  beats random at 2/12
  evolve     median 0.3148  IQR 0.3035..0.5590  range 0.2516..0.6697  beats random at 2/12
seed    random  hillclimb   evolve
   1    0.3664    0.2718    0.3117
   2    0.6727    0.2812    0.3277
   3    0.6952    0.2750    0.2927
   4    0.5532    0.3407    0.5289
   5    0.6129    0.5840    0.6618
   6    0.6071    0.5422    0.2975
   7    0.6675    0.3764    0.3055
   8    0.8148    0.2851    0.3060
   9    0.5484    0.6258    0.2516
  10    0.3743    0.6900    0.6697
  11    0.7312    0.3178    0.6492
  12    0.5625    0.5581    0.3179

forage, 4 hidden (44 genes), 300 evals x 12 seeds
  random     median 0.5028  IQR 0.4219..0.6447  range 0.3262..0.7796
  hill_climb median 0.2950  IQR 0.1853..0.4632  range -0.1219..0.9129  beats random at 3/12
  evolve     median 0.3350  IQR 0.3014..0.6863  range 0.2829..0.9002  beats random at 4/12
seed    random  hillclimb   evolve
   1    0.4740   -0.1157    0.9002
   2    0.7796    0.2947    0.7540
   3    0.6657   -0.1219    0.3122
   4    0.4063    0.3213    0.2829
   5    0.4423    0.5242    0.3378
   6    0.3262    0.2856    0.2926
   7    0.4062    0.5214    0.2854
   8    0.6669    0.2878    0.6860
   9    0.6376    0.9129    0.6871
  10    0.5316    0.4438    0.3322
  11    0.5619    0.2953    0.3044
  12    0.4271   -0.1219    0.6736

forage, 8 hidden (112 genes), 300 evals x 12 seeds
  random     median 0.5671  IQR 0.5448..0.6087  range 0.3303..0.6792
  hill_climb median 0.5528  IQR 0.3239..0.6554  range -0.1157..0.7011  beats random at 7/12
  evolve     median 0.4456  IQR 0.3172..0.6144  range 0.2478..0.6799  beats random at 4/12
seed    random  hillclimb   evolve
   1    0.3303    0.3871    0.2478
   2    0.5660    0.2666    0.5888
   3    0.6792    0.4551    0.3302
   4    0.5550    0.2889    0.3031
   5    0.5876    0.6622    0.6590
   6    0.5330    0.3355    0.6799
   7    0.5488    0.7011    0.2916
   8    0.5949    0.6524    0.3267
   9    0.6501    0.6506    0.6389
  10    0.5682    0.6706    0.6062
  11    0.6564   -0.1157    0.5610
  12    0.5301    0.6531    0.3219

forage, 16 hidden (344 genes), 300 evals x 12 seeds
  random     median 0.6006  IQR 0.5133..0.6367  range 0.4392..0.7206
  hill_climb median 0.3264  IQR 0.2805..0.5556  range -0.1219..0.6852  beats random at 2/12
  evolve     median 0.5060  IQR 0.3632..0.6406  range 0.2507..0.9207  beats random at 4/12
seed    random  hillclimb   evolve
   1    0.6637    0.5456    0.3898
   2    0.7206    0.2447    0.6942
   3    0.5309    0.6852    0.5896
   4    0.4508    0.2256    0.2507
   5    0.6609    0.6775    0.5596
   6    0.5889    0.3156    0.3181
   7    0.4606    0.3372    0.6469
   8    0.6124    0.5855    0.4525
   9    0.6145   -0.1219    0.6385
  10    0.6287    0.3144    0.9207
  11    0.5752    0.4550    0.3740
  12    0.4392    0.2925    0.3309

orbit, 0 hidden (8 genes), 300 evals x 12 seeds
  random     median 0.4792  IQR 0.4213..0.6322  range 0.2698..0.7944
  hill_climb median 0.7763  IQR 0.0987..0.8713  range 0.0949..0.9485  beats random at 7/12
  evolve     median 0.0998  IQR 0.0960..0.2905  range 0.0957..0.9538  beats random at 3/12
seed    random  hillclimb   evolve
   1    0.6695    0.9282    0.8621
   2    0.2698    0.0957    0.0960
   3    0.6526    0.0996    0.0962
   4    0.2967    0.9485    0.1000
   5    0.6254    0.9370    0.9465
   6    0.4502    0.8523    0.0957
   7    0.6202    0.7808    0.1000
   8    0.5083    0.6877    0.0959
   9    0.4474    0.0960    0.1000
  10    0.7944    0.7718    0.0959
  11    0.3722    0.0949    0.9538
  12    0.4376    0.7941    0.0996

orbit, 2 hidden (22 genes), 300 evals x 12 seeds
  random     median 0.6520  IQR 0.4962..0.6891  range 0.4425..0.7571
  hill_climb median 0.7293  IQR 0.0961..0.7730  range 0.0906..0.9060  beats random at 6/12
  evolve     median 0.0963  IQR 0.0947..0.8627  range 0.0927..0.9440  beats random at 4/12
seed    random  hillclimb   evolve
   1    0.6832    0.9060    0.8513
   2    0.6094    0.7296    0.0956
   3    0.7571    0.7436    0.0965
   4    0.6707    0.6526    0.0939
   5    0.4964    0.8304    0.8971
   6    0.7327    0.0962    0.0962
   7    0.6551    0.0959    0.0927
   8    0.4953    0.7539    0.9440
   9    0.4425    0.8625    0.0950
  10    0.4892    0.0906    0.9016
  11    0.6488    0.0947    0.1000
  12    0.7071    0.7291    0.0933

orbit, 4 hidden (44 genes), 300 evals x 12 seeds
  random     median 0.6504  IQR 0.6033..0.7520  range 0.5651..0.8720
  hill_climb median 0.0982  IQR 0.0961..0.4203  range 0.0946..0.8596  beats random at 3/12
  evolve     median 0.2089  IQR 0.0958..0.8814  range 0.0949..0.9550  beats random at 5/12
seed    random  hillclimb   evolve
   1    0.8082    0.2774    0.0950
   2    0.7205    0.2741    0.0949
   3    0.5651    0.0962    0.8750
   4    0.5928    0.8498    0.3212
   5    0.7493    0.8490    0.0966
   6    0.6442    0.0956    0.0959
   7    0.8720    0.0964    0.8721
   8    0.6095    0.0946    0.9522
   9    0.6566    0.8596    0.9007
  10    0.5959    0.1000    0.0961
  11    0.7604    0.0958    0.0956
  12    0.6058    0.0964    0.9550

orbit, 8 hidden (112 genes), 300 evals x 12 seeds
  random     median 0.6799  IQR 0.5867..0.7610  range 0.3582..0.7965
  hill_climb median 0.6372  IQR 0.0991..0.8609  range 0.0714..0.9307  beats random at 6/12
  evolve     median 0.8926  IQR 0.0990..0.9436  range 0.0594..0.9989  beats random at 7/12
seed    random  hillclimb   evolve
   1    0.3582    0.5808    0.1000
   2    0.6321    0.2834    0.8955
   3    0.4488    0.8572    0.9989
   4    0.7792    0.1000    0.9417
   5    0.7965    0.8800    0.8897
   6    0.6855    0.0714    0.9494
   7    0.7607    0.8721    0.0962
   8    0.6409    0.9307    0.2602
   9    0.7429    0.8308    0.9524
  10    0.4506    0.0962    0.9162
  11    0.6744    0.0961    0.0958
  12    0.7621    0.6936    0.0594

orbit, 16 hidden (344 genes), 300 evals x 12 seeds
  random     median 0.6677  IQR 0.5929..0.7461  range 0.3301..0.8890
  hill_climb median 0.3834  IQR 0.0965..0.8135  range 0.0944..0.9612  beats random at 4/12
  evolve     median 0.5612  IQR 0.3266..0.8592  range 0.0964..0.9518  beats random at 5/12
seed    random  hillclimb   evolve
   1    0.8358    0.5337    0.9462
   2    0.6627    0.9546    0.0964
   3    0.8787    0.7915    0.8204
   4    0.6727    0.0944    0.3453
   5    0.7086    0.9612    0.8920
   6    0.6068    0.0952    0.9518
   7    0.3301    0.0966    0.3735
   8    0.8890    0.2330    0.7489
   9    0.5603    0.8796    0.2702
  10    0.6038    0.7833    0.8483
  11    0.4560    0.0961    0.3616
  12    0.7162    0.0996    0.0966

track, 0 hidden (8 genes), 300 evals x 12 seeds
  random     median 0.3624  IQR 0.3507..0.4224  range 0.3231..0.5478
  hill_climb median 0.3746  IQR 0.3198..0.6091  range 0.2380..1.0171  beats random at 5/12
  evolve     median 0.3955  IQR 0.2909..0.5244  range 0.2123..0.7575  beats random at 7/12
seed    random  hillclimb   evolve
   1    0.3231    0.2380    0.3466
   2    0.4135    0.7307    0.4179
   3    0.3513    0.7175    0.2897
   4    0.3646    0.3294    0.2639
   5    0.3521    1.0171    0.4998
   6    0.3399    0.5729    0.5581
   7    0.5478    0.4520    0.7575
   8    0.3489    0.4134    0.5228
   9    0.4103    0.3151    0.3731
  10    0.3603    0.2595    0.2123
  11    0.4491    0.3359    0.2913
  12    0.5225    0.3214    0.5293

track, 2 hidden (22 genes), 300 evals x 12 seeds
  random     median 0.4401  IQR 0.3824..0.6387  range 0.3211..1.0435
  hill_climb median 0.3413  IQR 0.2366..0.5005  range 0.1883..0.7565  beats random at 3/12
  evolve     median 0.4333  IQR 0.2444..0.6815  range 0.1897..0.9092  beats random at 4/12
seed    random  hillclimb   evolve
   1    0.3397    0.2857    0.2631
   2    0.3656    0.3215    0.6412
   3    0.5944    0.7565    0.2140
   4    0.4127    0.2256    0.1897
   5    0.8063    0.4327    0.5863
   6    0.4471    0.2164    0.2802
   7    0.4331    0.3611    0.2529
   8    0.5593    0.2402    0.6993
   9    1.0435    0.3705    0.6756
  10    0.3211    0.7549    0.8426
  11    0.7716    0.1883    0.9092
  12    0.3880    0.7041    0.2191

track, 4 hidden (44 genes), 300 evals x 12 seeds
  random     median 0.4375  IQR 0.3755..0.5218  range 0.3494..0.6193
  hill_climb median 0.3141  IQR 0.2602..0.5419  range 0.2083..0.9715  beats random at 3/12
  evolve     median 0.3516  IQR 0.2523..0.6631  range 0.1897..0.9281  beats random at 5/12
seed    random  hillclimb   evolve
   1    0.4327    0.5569    0.2189
   2    0.3761    0.2838    0.2528
   3    0.4267    0.2812    0.2774
   4    0.3735    0.2083    0.4839
   5    0.6014    0.9715    0.8072
   6    0.4423    0.2196    0.3661
   7    0.5068    0.3855    0.3372
   8    0.4959    0.2611    0.6178
   9    0.6193    0.2574    0.9281
  10    0.5670    0.5524    0.2507
  11    0.3494    0.5383    0.1897
  12    0.3715    0.3445    0.7989

track, 8 hidden (112 genes), 300 evals x 12 seeds
  random     median 0.5277  IQR 0.4634..0.6456  range 0.3776..0.7014
  hill_climb median 0.2481  IQR 0.2184..0.5387  range 0.2023..0.7334  beats random at 2/12
  evolve     median 0.5196  IQR 0.2441..0.7044  range 0.1897..0.8671  beats random at 6/12
seed    random  hillclimb   evolve
   1    0.6520    0.2367    0.2249
   2    0.3776    0.2485    0.6831
   3    0.5281    0.7334    0.2828
   4    0.5021    0.2478    0.8671
   5    0.4726    0.5216    0.7683
   6    0.3992    0.2065    0.5647
   7    0.5954    0.2127    0.1897
   8    0.5272    0.4037    0.5747
   9    0.6434    0.5902    0.4745
  10    0.6581    0.2204    0.2490
  11    0.4357    0.2023    0.7720
  12    0.7014    0.6412    0.2293

track, 16 hidden (344 genes), 300 evals x 12 seeds
  random     median 0.5724  IQR 0.5184..0.6919  range 0.4208..0.7523
  hill_climb median 0.2704  IQR 0.1976..0.4542  range 0.1882..0.8158  beats random at 3/12
  evolve     median 0.3018  IQR 0.2473..0.5000  range 0.1882..0.7214  beats random at 2/12
seed    random  hillclimb   evolve
   1    0.7490    0.3175    0.5017
   2    0.6733    0.1918    0.7214
   3    0.4208    0.6917    0.1882
   4    0.4251    0.2829    0.2475
   5    0.5535    0.3751    0.3205
   6    0.5575    0.1995    0.4994
   7    0.7523    0.1882    0.3545
   8    0.5203    0.2579    0.2495
   9    0.7474    0.2488    0.2467
  10    0.5873    0.7817    0.2449
  11    0.5126    0.1897    0.5413
  12    0.6393    0.8158    0.2831

```

## Setting `immigrants` (mu 12, lambda 24, crossover 0.3, immigrantRate 0.25)

```
forage, 0 hidden (8 genes), 300 evals x 12 seeds
  random     median 0.5080  IQR 0.4341..0.5740  range 0.2498..0.6533
  hill_climb median 0.3855  IQR 0.3249..0.6767  range 0.2769..0.9047  beats random at 5/12
  evolve     median 0.6312  IQR 0.5450..0.7091  range 0.2676..0.8799  beats random at 9/12
seed    random  hillclimb   evolve
   1    0.2597    0.9047    0.2814
   2    0.4126    0.6865    0.7812
   3    0.5442    0.3675    0.5624
   4    0.6395    0.3345    0.6731
   5    0.4412    0.6378    0.6851
   6    0.4903    0.3257    0.2676
   7    0.5581    0.4035    0.6370
   8    0.5258    0.3224    0.7900
   9    0.6533    0.6734    0.6255
  10    0.2498    0.8450    0.5848
  11    0.4451    0.2969    0.8799
  12    0.6216    0.2769    0.4926

forage, 4 hidden (44 genes), 300 evals x 12 seeds
  random     median 0.5028  IQR 0.4219..0.6447  range 0.3262..0.7796
  hill_climb median 0.2950  IQR 0.1853..0.4632  range -0.1219..0.9129  beats random at 3/12
  evolve     median 0.6519  IQR 0.5086..0.6945  range 0.2940..0.9105  beats random at 8/12
seed    random  hillclimb   evolve
   1    0.4740   -0.1157    0.6512
   2    0.7796    0.2947    0.3359
   3    0.6657   -0.1219    0.5662
   4    0.4063    0.3213    0.7971
   5    0.4423    0.5242    0.5999
   6    0.3262    0.2856    0.2959
   7    0.4062    0.5214    0.6527
   8    0.6669    0.2878    0.8860
   9    0.6376    0.9129    0.9105
  10    0.5316    0.4438    0.6531
  11    0.5619    0.2953    0.6603
  12    0.4271   -0.1219    0.2940

forage, 16 hidden (344 genes), 300 evals x 12 seeds
  random     median 0.6006  IQR 0.5133..0.6367  range 0.4392..0.7206
  hill_climb median 0.3264  IQR 0.2805..0.5556  range -0.1219..0.6852  beats random at 2/12
  evolve     median 0.6616  IQR 0.5811..0.8290  range 0.4269..0.9079  beats random at 8/12
seed    random  hillclimb   evolve
   1    0.6637    0.5456    0.8669
   2    0.7206    0.2447    0.9079
   3    0.5309    0.6852    0.4580
   4    0.4508    0.2256    0.6854
   5    0.6609    0.6775    0.6049
   6    0.5889    0.3156    0.4269
   7    0.4606    0.3372    0.6132
   8    0.6124    0.5855    0.5096
   9    0.6145   -0.1219    0.8164
  10    0.6287    0.3144    0.8999
  11    0.5752    0.4550    0.6442
  12    0.4392    0.2925    0.6790

orbit, 0 hidden (8 genes), 300 evals x 12 seeds
  random     median 0.4792  IQR 0.4213..0.6322  range 0.2698..0.7944
  hill_climb median 0.7763  IQR 0.0987..0.8713  range 0.0949..0.9485  beats random at 7/12
  evolve     median 0.8456  IQR 0.7007..0.8878  range 0.2352..0.9560  beats random at 11/12
seed    random  hillclimb   evolve
   1    0.6695    0.9282    0.7115
   2    0.2698    0.0957    0.5940
   3    0.6526    0.0996    0.6683
   4    0.2967    0.9485    0.7615
   5    0.6254    0.9370    0.9560
   6    0.4502    0.8523    0.9075
   7    0.6202    0.7808    0.8427
   8    0.5083    0.6877    0.8876
   9    0.4474    0.0960    0.8849
  10    0.7944    0.7718    0.2352
  11    0.3722    0.0949    0.8485
  12    0.4376    0.7941    0.8885

orbit, 4 hidden (44 genes), 300 evals x 12 seeds
  random     median 0.6504  IQR 0.6033..0.7520  range 0.5651..0.8720
  hill_climb median 0.0982  IQR 0.0961..0.4203  range 0.0946..0.8596  beats random at 3/12
  evolve     median 0.8472  IQR 0.7841..0.8756  range 0.1896..0.9484  beats random at 9/12
seed    random  hillclimb   evolve
   1    0.8082    0.2774    0.7695
   2    0.7205    0.2741    0.8666
   3    0.5651    0.0962    0.9484
   4    0.5928    0.8498    0.8210
   5    0.7493    0.8490    0.8608
   6    0.6442    0.0956    0.9096
   7    0.8720    0.0964    0.1896
   8    0.6095    0.0946    0.8636
   9    0.6566    0.8596    0.8337
  10    0.5959    0.1000    0.3870
  11    0.7604    0.0958    0.7890
  12    0.6058    0.0964    0.9027

orbit, 16 hidden (344 genes), 300 evals x 12 seeds
  random     median 0.6677  IQR 0.5929..0.7461  range 0.3301..0.8890
  hill_climb median 0.3834  IQR 0.0965..0.8135  range 0.0944..0.9612  beats random at 4/12
  evolve     median 0.8873  IQR 0.8356..0.9334  range 0.1000..0.9534  beats random at 11/12
seed    random  hillclimb   evolve
   1    0.8358    0.5337    0.9534
   2    0.6627    0.9546    0.8365
   3    0.8787    0.7915    0.9157
   4    0.6727    0.0944    0.9401
   5    0.7086    0.9612    0.9311
   6    0.6068    0.0952    0.8330
   7    0.3301    0.0966    0.8369
   8    0.8890    0.2330    0.9492
   9    0.5603    0.8796    0.6437
  10    0.6038    0.7833    0.9117
  11    0.4560    0.0961    0.1000
  12    0.7162    0.0996    0.8628

track, 0 hidden (8 genes), 300 evals x 12 seeds
  random     median 0.3624  IQR 0.3507..0.4224  range 0.3231..0.5478
  hill_climb median 0.3746  IQR 0.3198..0.6091  range 0.2380..1.0171  beats random at 5/12
  evolve     median 0.5928  IQR 0.5339..0.6928  range 0.4034..0.7340  beats random at 11/12
seed    random  hillclimb   evolve
   1    0.3231    0.2380    0.4034
   2    0.4135    0.7307    0.5399
   3    0.3513    0.7175    0.5526
   4    0.3646    0.3294    0.7340
   5    0.3521    1.0171    0.7305
   6    0.3399    0.5729    0.7027
   7    0.5478    0.4520    0.6895
   8    0.3489    0.4134    0.5708
   9    0.4103    0.3151    0.5158
  10    0.3603    0.2595    0.6148
  11    0.4491    0.3359    0.6743
  12    0.5225    0.3214    0.5027

track, 4 hidden (44 genes), 300 evals x 12 seeds
  random     median 0.4375  IQR 0.3755..0.5218  range 0.3494..0.6193
  hill_climb median 0.3141  IQR 0.2602..0.5419  range 0.2083..0.9715  beats random at 3/12
  evolve     median 0.6121  IQR 0.4866..0.7215  range 0.3800..0.9175  beats random at 11/12
seed    random  hillclimb   evolve
   1    0.4327    0.5569    0.5192
   2    0.3761    0.2838    0.6758
   3    0.4267    0.2812    0.7680
   4    0.3735    0.2083    0.3800
   5    0.6014    0.9715    0.7060
   6    0.4423    0.2196    0.5485
   7    0.5068    0.3855    0.6780
   8    0.4959    0.2611    0.7876
   9    0.6193    0.2574    0.9175
  10    0.5670    0.5524    0.4987
  11    0.3494    0.5383    0.4501
  12    0.3715    0.3445    0.3902

track, 16 hidden (344 genes), 300 evals x 12 seeds
  random     median 0.5724  IQR 0.5184..0.6919  range 0.4208..0.7523
  hill_climb median 0.2704  IQR 0.1976..0.4542  range 0.1882..0.8158  beats random at 3/12
  evolve     median 0.7487  IQR 0.5328..0.8479  range 0.3169..0.9024  beats random at 8/12
seed    random  hillclimb   evolve
   1    0.7490    0.3175    0.5522
   2    0.6733    0.1918    0.7807
   3    0.4208    0.6917    0.4745
   4    0.4251    0.2829    0.8435
   5    0.5535    0.3751    0.7221
   6    0.5575    0.1995    0.8612
   7    0.7523    0.1882    0.6845
   8    0.5203    0.2579    0.9024
   9    0.7474    0.2488    0.3169
  10    0.5873    0.7817    0.7752
  11    0.5126    0.1897    0.3536
  12    0.6393    0.8158    0.8889

```
