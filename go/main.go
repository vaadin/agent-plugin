// Command vaadin-agent-tools is a self-contained native CLI of Vaadin tools for
// AI agents (and humans). The CLI surface (tool names, args, --json shape, exit
// codes) is the contract the plugin's skills and hook rely on.
package main

import (
	"os"

	"github.com/vaadin/agent-plugin/internal/cli"
)

func main() {
	os.Exit(cli.Run(os.Args[1:]))
}
