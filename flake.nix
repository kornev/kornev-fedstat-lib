{
  description = "A Clojure development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let inherit (nixpkgs.lib) genAttrs;
        supportedSystems = [
          "x86_64-linux"
          "aarch64-darwin"
        ];
        forAllSystems = f: genAttrs supportedSystems (system: f system);
    in {
      devShells = forAllSystems (system:
        let pkgs = import nixpkgs {
              inherit system;
              config.allowUnfree = true;
            };
        in {
          default = pkgs.mkShell {
            buildInputs = with pkgs; [
              babashka
            ];
            shellHook = ''
              # General
              export TZ="Europe/Moscow"
              export PROJECT_HOME="$PWD"

              # Java
              export JAVA_HOME="${ pkgs.jdk25.home }"
              export M2_HOME="$PROJECT_HOME/.m2"
            '';
          };
        }
      );
    };
}

