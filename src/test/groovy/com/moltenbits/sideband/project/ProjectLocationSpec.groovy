package com.moltenbits.sideband.project

import spock.lang.Specification

import java.nio.file.Path

class ProjectLocationSpec extends Specification {

    void "the default name is the root directory's name, normalized to the name rule: #directory"() {
        expect:
        new ProjectLocation(Path.of("/work/$directory/.git"), Path.of("/work/$directory")).defaultName() == expected

        where:
        directory                                       | expected
        "sideband"                                      | "sideband"
        "Listenly"                                      | "listenly"
        "My Project_2"                                  | "my-project-2"
        "a--b..c"                                       | "a-b-c"
        "2048-game"                                     | "game"
        "trailing-"                                     | "trailing"
        "x" * 40                                        | "x" * 32
        "abcdefghijklmnopqrstuvwxyz01234-6789"          | "abcdefghijklmnopqrstuvwxyz01234"
        "1234"                                          | null
        "日本"                                           | null
    }

    void "a root with no name has no default name"() {
        expect:
        new ProjectLocation(Path.of("/"), Path.of("/")).defaultName() == null
    }
}
