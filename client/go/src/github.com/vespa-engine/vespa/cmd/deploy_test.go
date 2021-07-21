// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// deploy command tests
// Author: bratseth

package cmd

import (
    "github.com/stretchr/testify/assert"
    "testing"
)

func TestDeployCommand(t *testing.T) {
    expectedUrl = "127.0.0.1:19071/application/v2/tenant/default/prepareandactivate"
	assert.Equal(t,
	             "",
	             executeCommand(t, []string{"deploy", "testdata/application.zip"}),
	             "vespa status config-server")
}
