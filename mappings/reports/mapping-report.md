# Mapping report

Classes named: 4515

Fields named: 9677

Methods named: 19290

From the open-source libraries (samskivert, Clyde, Narya, Nenya; they win over personal names): 737 classes, 1257 fields, 6270 methods.

Where a personal name and the source disagreed: 109 (listed in conflicts.md)

Personal mappings merged: 126 files; 35 classes, 42 fields, 61 methods, 5928 methods with parameter names (they win over every other name).

Not found in the jar (the game changed, or it is another jar): 0 fields, 1 methods.

* name com/samskivert/util/AbstractIntSet for com/samskivert/util/x (personal) is taken by com/samskivert/util/a: not used
* not applied, it overrides something outside the jar: com/samskivert/util/HashIntMap|entrySet|()Ljava/util/Set; -> intEntrySet
* two names in one group of overriding methods: getChatDirector (source) / getChatManager (personal): kept getChatDirector
* two names in one group of overriding methods: flip (source) / getBuffer (source): kept flip
* two names in one group of overriding methods: dynamicInterface (personal) / getDObjectManager (source): kept getDObjectManager
* two names in one group of overriding methods: getConfig (source) / config (personal): kept getConfig
* two names in one group of overriding methods: client (personal) / getClient (source): kept getClient
* two names in one group of overriding methods: requestFailed (source) / setInvocationId (source): kept requestFailed

## By kind of evidence

### Classes
| evidence | count |
|---|---|
| extends | 1332 |
| field | 78 |
| implements | 547 |
| loads | 110 |
| method | 31 |
| no evidence | 1643 |
| personal | 35 |
| setter | 2 |
| source | 737 |

### Fields
| evidence | count |
|---|---|
| assigned from | 269 |
| constant text | 2 |
| personal | 42 |
| returned by | 120 |
| source | 1257 |
| stored from | 505 |
| type | 7482 |

### Methods
| evidence | count |
|---|---|
| bridge | 786 |
| creates | 663 |
| personal | 61 |
| returns field | 912 |
| sets field | 164 |
| signature | 8674 |
| source | 6270 |
| uses the text | 1174 |
| wraps | 586 |
