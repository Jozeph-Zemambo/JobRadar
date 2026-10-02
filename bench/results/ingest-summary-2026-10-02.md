| limiter | N boards | mode | median wall ms | min-max wall ms | median fetch ms | median persist ms | postings | speedup vs sequential |
|---|---|---|---|---|---|---|---|---|
| off | 15 | SEQUENTIAL | 6889 | 6390-8242 | 6262 | 626 | 4212 | 1.0x |
| off | 15 | PLATFORM_POOL | 1242 | 1233-1331 | 636 | 604 | 4212 | 5.5x |
| off | 15 | VIRTUAL | 1256 | 1158-1282 | 644 | 598 | 4212 | 5.5x |
| off | 30 | SEQUENTIAL | 13680 | 12672-14556 | 12727 | 952 | 6684 | 1.0x |
| off | 30 | PLATFORM_POOL | 2080 | 1918-2119 | 1114 | 936 | 6684 | 6.6x |
| off | 30 | VIRTUAL | 1606 | 1569-1661 | 654 | 949 | 6684 | 8.5x |
| off | 45 | SEQUENTIAL | 20581 | 18426-21225 | 19277 | 1303 | 9479 | 1.0x |
| off | 45 | PLATFORM_POOL | 2765 | 2641-2871 | 1470 | 1309 | 9479 | 7.4x |
| off | 45 | VIRTUAL | 2088 | 1993-2120 | 762 | 1314 | 9479 | 9.9x |
| polite | 15 | SEQUENTIAL | 7235 | 6749-7345 | 6642 | 593 | 4212 | 1.0x |
| polite | 15 | PLATFORM_POOL | 2675 | 2525-2724 | 2085 | 599 | 4212 | 2.7x |
| polite | 15 | VIRTUAL | 2719 | 2535-2729 | 2109 | 604 | 4212 | 2.7x |
| polite | 30 | SEQUENTIAL | 14421 | 14354-15348 | 13474 | 942 | 6684 | 1.0x |
| polite | 30 | PLATFORM_POOL | 6254 | 6126-6553 | 5308 | 946 | 6684 | 2.3x |
| polite | 30 | VIRTUAL | 5442 | 5252-5522 | 4506 | 933 | 6684 | 2.6x |
| polite | 45 | SEQUENTIAL | 22516 | 22108-22773 | 21200 | 1314 | 9479 | 1.0x |
| polite | 45 | PLATFORM_POOL | 11733 | 11352-11771 | 10408 | 1332 | 9479 | 1.9x |
| polite | 45 | VIRTUAL | 8347 | 8210-8522 | 7046 | 1307 | 9479 | 2.7x |
