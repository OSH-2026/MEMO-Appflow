# Meeting 5

Time: 2026/03/29

## Partipicants

- Mosha Huang(AndyHuang-hub)
- Chengyu Fan(fancyovo)
- Kewei Chen(javaherobrine)
- Jingyi Guo(placebot)

## Abstract

With limited resources in mobile devices, we must balance between prediction precision and prediction overhead. Using good LLMs will introduce extra unnecessary overhead, 
but using bad LLMs will decrease the precision. For this situation, Kewei Chen suggested `Circuit Breaker`. Put it clearly, use good models when the device is free and fall 
back to models with less resource consumption. But, with limited battery, it must have a limited maximum overhead as mobile devices are sensitive to power consumption.
